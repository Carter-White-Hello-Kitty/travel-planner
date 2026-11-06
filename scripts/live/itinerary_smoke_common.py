"""Shared real-HTTP helpers for manual itinerary smoke tests.

The app must already be running with its development database and Google API key.
Only a local HTTP target is accepted because these tests create users and trips.
"""

import http.cookiejar
import json
import os
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlparse


CITY_CENTER = {"latitude": 40.758, "longitude": -73.9855}
START_DATE = (date.today() + timedelta(days=30)).isoformat()


class SmokeClient:
    def __init__(self):
        base = os.environ.get("SMOKE_URL", "http://localhost:8085").rstrip("/")
        target = urlparse(base)
        if (target.scheme != "http" or target.hostname not in ("localhost", "127.0.0.1")
                or not target.port or target.path or target.query or target.fragment):
            raise ValueError("SMOKE_URL must be http://localhost:<port> or http://127.0.0.1:<port>")
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        )

    def call(self, path, body=None, token=None, expected=200):
        headers = {"Origin": "http://localhost:3000", "Accept": "application/json"}
        if body is not None:
            headers.update({
                "Content-Type": "application/json",
                "X-Requested-With": "XMLHttpRequest",
                "X-XSRF-TOKEN": self.call("/auth/csrf")["data"]["token"],
            })
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(
            self.base + path,
            data=None if body is None else json.dumps(body).encode("utf-8"),
            headers=headers,
        )
        try:
            response = self.opener.open(request, timeout=90)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = json.load(response)
            status = response.status
        if not isinstance(payload, dict):
            raise AssertionError(f"{path}: expected a JSON object")
        if status != expected:
            detail = payload.get("error") or {}
            code = detail.get("code", "unknown") if isinstance(detail, dict) else "unknown"
            raise AssertionError(f"{path}: HTTP {status}, error code {code}")
        return payload

    def register_and_login(self):
        credentials = {
            "email": "itinerary-smoke-" + uuid.uuid4().hex + "@example.com",
            "password": "LocalItinerarySmokePassword2026!",
        }
        self.call("/auth/register", credentials, expected=201)
        return self.call("/auth/login", credentials)["data"]["accessToken"]

    def search_pois(self, token):
        response = self.call("/pois/search", {
            "keyword": "tourist attractions in Manhattan New York",
            **CITY_CENTER,
            "radius": 15000,
        }, token=token)
        data = response.get("data")
        pois = data.get("pois") if isinstance(data, dict) else None
        if not isinstance(pois, list):
            raise AssertionError("/pois/search did not return a POI list")
        usable = []
        seen = set()
        for poi in pois:
            if not isinstance(poi, dict):
                continue
            place_id = poi.get("googlePlaceId")
            if (not isinstance(place_id, str) or not place_id or place_id in seen
                    or poi.get("businessStatus") in ("CLOSED_PERMANENTLY", "CLOSED_TEMPORARILY")
                    or not isinstance(poi.get("latitude"), (int, float))
                    or not isinstance(poi.get("longitude"), (int, float))):
                continue
            seen.add(place_id)
            usable.append(poi)
        return usable

    def generate_and_verify(self, token, request):
        response = self.call("/trips", request, token=token, expected=201)
        data = response.get("data")
        if response.get("message") != "success" or not isinstance(data, dict):
            raise AssertionError("/trips returned an invalid success response")
        if (not isinstance(data.get("tripId"), int) or data.get("tripId") < 1
                or data.get("name") != request["name"]
                or data.get("startDate") != request["startDate"]
                or data.get("status") != "Planning"
                or data.get("dayCount") != request["dayCount"]):
            raise AssertionError("/trips returned incorrect trip metadata")
        days = data.get("tripDays")
        if not isinstance(days, list) or len(days) != request["dayCount"]:
            raise AssertionError("/trips returned an incorrect number of days")
        seen = set()
        for expected_day, day in enumerate(days, start=1):
            if not isinstance(day, dict):
                raise AssertionError(f"Day {expected_day} must be an object")
            pois = day.get("tripPois")
            if (day.get("dayNumber") != expected_day or not isinstance(pois, list)
                    or not 1 <= len(pois) <= request["constraints"]["maxPoisPerDay"]):
                raise AssertionError(f"Day {expected_day} has invalid POI count or ordering")
            for poi in pois:
                if not isinstance(poi, dict):
                    raise AssertionError("Generated POI must be an object")
                place_id = poi.get("googlePlaceId")
                if (not isinstance(place_id, str) or not place_id or place_id in seen
                        or not isinstance(poi.get("tripPoiId"), int)
                        or not isinstance(poi.get("latitude"), (int, float))
                        or not isinstance(poi.get("longitude"), (int, float))
                        or poi.get("status") != "Planned"):
                    raise AssertionError("Generated POI is incomplete or duplicated")
                seen.add(place_id)

        listed = self.call("/trips", token=token).get("data")
        if not isinstance(listed, list) or not any(
                item.get("tripId") == data["tripId"] and item.get("status") == "Planning"
                for item in listed if isinstance(item, dict)):
            raise AssertionError("Generated trip was not found in the saved trip list")
        return response


def save_response(filename, response):
    path = Path(__file__).resolve().parent / filename
    path.write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
