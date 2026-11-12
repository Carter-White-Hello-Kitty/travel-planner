"""Generate one real Google POI, then fetch the saved trip's full detail over HTTP.

Run from the repository root with the development app and Docker services running:
    SMOKE_URL=http://localhost:8085 python3 scripts/smoke/trip-detail-smoke.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from util.smoke_common import CITY_CENTER, START_DATE, SmokeClient, save_response


def main():
    client = SmokeClient()
    token = client.register_and_login()
    request = {
        "name": "Manhattan detail smoke trip",
        "startDate": START_DATE,
        "dayCount": 1,
        "searchArea": {**CITY_CENTER, "radiusMeters": 15000},
        "constraints": {"maxPoisPerDay": 1, "preferredTypes": []},
    }

    generated = client.generate_and_verify(token, request).get("data")
    if not isinstance(generated, dict) or not isinstance(generated.get("tripId"), int):
        raise AssertionError("/trips did not create a trip")

    detail_response = client.call(f"/trips/{generated['tripId']}", token=token)
    detail = detail_response.get("data")
    if detail_response.get("message") != "success" or not isinstance(detail, dict):
        raise AssertionError("Trip detail response is incomplete")
    for field in ("tripId", "name", "startDate", "status", "dayCount"):
        if detail.get(field) != generated.get(field):
            raise AssertionError(f"Trip detail disagrees with the saved trip: {field}")

    generated_days = generated.get("tripDays")
    detail_days = detail.get("tripDays")
    if not isinstance(generated_days, list) or len(generated_days) != 1:
        raise AssertionError("Generated trip does not have exactly one day")
    if not isinstance(detail_days, list) or len(detail_days) != 1:
        raise AssertionError("Trip detail does not have exactly one day")
    generated_pois = generated_days[0].get("tripPois")
    detail_pois = detail_days[0].get("tripPois")
    if not isinstance(generated_pois, list) or len(generated_pois) != 1:
        raise AssertionError("Generated trip does not have exactly one POI")
    if not isinstance(detail_pois, list) or len(detail_pois) != 1:
        raise AssertionError("Trip detail does not have exactly one POI")

    original, restored = generated_pois[0], detail_pois[0]
    for field in ("tripPoiId", "googlePlaceId", "status"):
        if restored.get(field) != original.get(field):
            raise AssertionError(f"Trip detail changed the saved POI: {field}")
    if detail_days[0].get("dayNumber") != 1 or detail.get("status") != "Planning":
        raise AssertionError("Trip detail returned the wrong day or status")
    if not isinstance(restored.get("name"), str) or not restored["name"]:
        raise AssertionError("Google Places Details did not provide a POI name")
    if "address" not in restored:
        raise AssertionError("Trip detail omitted the Google address field")
    if not all(isinstance(restored.get(field), (int, float))
               for field in ("latitude", "longitude")):
        raise AssertionError("Google Places Details did not provide POI coordinates")

    path = save_response("trip-detail.json", detail_response)
    print(f"PASS: GET /trips/{detail['tripId']} restored {restored['name']} from real Google Places Details")
    print(f"Saved response: {path}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
