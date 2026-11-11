"""Run the wishlist flow over real HTTP, MySQL, and Google Places.

Start the backend with its local test database and GOOGLE_PLACES_API_KEY, then run:
    SMOKE_URL=http://localhost:18085 python3 scripts/smoke/wishlist-smoke.py
"""

import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from util.smoke_common import SmokeClient


def delete_place(client, token, place_id):
    csrf = client.call("/auth/csrf")["data"]["token"]
    path = "/wishlist/" + urllib.parse.quote(place_id, safe="")
    request = urllib.request.Request(
        client.base + path,
        method="DELETE",
        headers={
            "Authorization": "Bearer " + token,
            "Origin": "http://localhost:3000",
            "X-Requested-With": "XMLHttpRequest",
            "X-XSRF-TOKEN": csrf,
            "Accept": "application/json",
        },
    )
    try:
        response = client.opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = json.load(response)
        status = response.status
    if status != 200:
        code = payload.get("error", {}).get("code", "unknown")
        raise AssertionError(f"{path}: HTTP {status}, error code {code}")
    return payload


def main():
    client = SmokeClient()
    credentials = {
        "email": "wishlist-smoke-" + uuid.uuid4().hex + "@example.com",
        "password": "LocalWishlistSmokePassword2026!",
    }
    client.call("/auth/register", credentials, expected=201)
    token = client.call("/auth/login", credentials)["data"]["accessToken"]
    places = client.search_pois(token)
    candidates = [poi for poi in places if isinstance(poi.get("name"), str) and poi["name"]]
    if not candidates:
        raise AssertionError("Google Places returned no named POI to add to the wishlist")
    place_id = candidates[0]["googlePlaceId"]

    initial = client.call("/wishlist", token=token)["data"]
    if initial["items"] or initial["pagination"]["totalItems"] != 0:
        raise AssertionError("A new user's wishlist is not empty")

    added = client.call("/wishlist", {"googlePlaceId": place_id}, token=token, expected=201)
    if (added.get("message") != "POI added to wishlist successfully"
            or added["data"]["googlePlaceId"] != place_id
            or not added["data"].get("createdAt")):
        raise AssertionError("POST /wishlist did not return the saved place")

    duplicate = client.call("/wishlist", {"googlePlaceId": place_id}, token=token, expected=409)
    if duplicate.get("error", {}).get("code") != "WISHLIST_ITEM_ALREADY_EXISTS":
        raise AssertionError("A duplicate wishlist item was not rejected")

    listed = client.call("/wishlist?page=1&pageSize=20", token=token)["data"]
    if (listed["pagination"]["totalItems"] != 1 or len(listed["items"]) != 1
            or listed["items"][0]["googlePlaceId"] != place_id
            or not listed["items"][0].get("name")
            or not isinstance(listed["items"][0].get("latitude"), (int, float))
            or not isinstance(listed["items"][0].get("longitude"), (int, float))):
        raise AssertionError("GET /wishlist did not return the Google-enriched place")

    removed = delete_place(client, token, place_id)
    if (removed.get("message") != "POI removed from wishlist successfully"
            or removed["data"]["googlePlaceId"] != place_id):
        raise AssertionError("DELETE /wishlist/{googlePlaceId} returned an unexpected result")

    final = client.call("/wishlist", token=token)["data"]
    if final["items"] or final["pagination"]["totalItems"] != 0:
        raise AssertionError("The removed place is still in the wishlist")
    print("PASS: real Google search, wishlist add, duplicate rejection, list, and delete")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
