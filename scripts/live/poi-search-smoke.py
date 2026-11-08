"""Manually smoke test POI search against the running app and real Google Places.

Run from the repository root: python3 scripts/live/poi-search-smoke.py
This script creates one local test account and makes one Google Places request.
"""

import http.cookiejar
import json
import os
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from urllib.parse import urlparse


base = os.environ.get("SMOKE_URL", "http://localhost:8085").rstrip("/")
url = urlparse(base)
if url.scheme != "http" or url.hostname != "localhost" or not url.port or url.path:
    raise SystemExit("SMOKE_URL must be http://localhost:<port>")

client = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
)
output_path = Path(__file__).resolve().parent / "poi-search-result.json"


def call(path, body=None, access_token=None, csrf_token=None, expected=200):
    headers = {"Origin": "http://localhost:3000", "Accept": "application/json"}
    if body is not None:
        headers.update({
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "X-XSRF-TOKEN": csrf_token,
        })
    if access_token:
        headers["Authorization"] = "Bearer " + access_token
    request = urllib.request.Request(
        base + path,
        data=None if body is None else json.dumps(body).encode("utf-8"),
        headers=headers,
    )
    try:
        response = client.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = json.load(response)
        status = response.status
    if status != expected:
        code = payload.get("error", {}).get("code") if isinstance(payload, dict) else None
        raise AssertionError(f"{path}: HTTP {status}, error code {code or 'unknown'}")
    if not isinstance(payload, dict):
        raise AssertionError(f"{path}: expected a JSON object")
    return payload


def csrf():
    return call("/auth/csrf")["data"]["token"]


def main():
    email = "poi-smoke-" + uuid.uuid4().hex + "@example.com"
    password = "LocalPoiSmokePassword2026!"
    call("/auth/register", {"email": email, "password": password},
         csrf_token=csrf(), expected=201)
    access_token = call("/auth/login", {"email": email, "password": password},
                        csrf_token=csrf())["data"]["accessToken"]

    result = call("/pois/search", {
        "keyword": "museum in Manhattan New York",
        "latitude": 40.7794,
        "longitude": -73.9632,
        "radius": 10000,
    }, access_token=access_token, csrf_token=csrf())
    if result.get("message") != "POIs retrieved successfully":
        raise AssertionError("Unexpected POI search response message")
    pois = result.get("data", {}).get("pois")
    if not isinstance(pois, list) or not pois:
        raise AssertionError("POI search returned no places; cannot verify field mapping")
    for poi in pois:
        if not isinstance(poi, dict):
            raise AssertionError("POI must be a JSON object")
        required = ("googlePlaceId", "name", "address", "latitude", "longitude",
                    "primaryType", "types", "rating", "userRatingCount", "businessStatus")
        if any(field not in poi for field in required):
            raise AssertionError("POI response is missing one or more agreed fields")
        if not isinstance(poi["googlePlaceId"], str) or not poi["googlePlaceId"]:
            raise AssertionError("POI is missing a Google Place ID")
        if not all(isinstance(poi[field], (int, float)) for field in ("latitude", "longitude")):
            raise AssertionError("POI is missing coordinates")
        if not isinstance(poi["types"], list):
            raise AssertionError("POI types must be an array")
        if poi["userRatingCount"] is not None and (
            not isinstance(poi["userRatingCount"], int) or poi["userRatingCount"] < 0
        ):
            raise AssertionError("POI userRatingCount must be a nonnegative integer or null")
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: authenticated /pois/search returned {len(pois)} Google places with agreed fields")
    print(f"Saved response: {output_path}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
