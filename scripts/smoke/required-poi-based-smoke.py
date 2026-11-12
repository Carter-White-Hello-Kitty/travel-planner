"""Generate a trip with known real Google POIs on days 1 and 3.

Run from the repository root: python3 scripts/smoke/required-poi-based-smoke.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from util.smoke_common import REQUIRED_PLACE_IDS, START_DATE, SmokeClient, save_response


def main():
    client = SmokeClient()
    token = client.register_and_login()
    required_ids = [place_id for _, place_id in REQUIRED_PLACE_IDS]
    request = {
        "name": "Manhattan required POI smoke trip",
        "startDate": START_DATE,
        "dayCount": 3,
        "constraints": {"maxPoisPerDay": 3, "preferredTypes": []},
        "tripDays": [
            {"dayNumber": 1, "mustVisitPois": [
                {"googlePlaceId": required_ids[0]},
                {"googlePlaceId": required_ids[1]},
            ]},
            {"dayNumber": 2, "mustVisitPois": []},
            {"dayNumber": 3, "mustVisitPois": [
                {"googlePlaceId": required_ids[2]},
            ]},
        ],
    }
    response = client.generate_and_verify(token, request)
    days = response["data"]["tripDays"]
    first_day = [poi["googlePlaceId"] for poi in days[0]["tripPois"]]
    third_day = [poi["googlePlaceId"] for poi in days[2]["tripPois"]]
    if (required_ids[0] not in first_day or required_ids[1] not in first_day
            or first_day.index(required_ids[0]) >= first_day.index(required_ids[1])
            or required_ids[2] not in third_day):
        raise AssertionError("Required POIs were not preserved on the requested days and order")
    path = save_response("required-poi-based.json", response)
    print("Required Google POIs:")
    for day, (name, place_id) in ((1, REQUIRED_PLACE_IDS[0]), (1, REQUIRED_PLACE_IDS[1]),
                                  (3, REQUIRED_PLACE_IDS[2])):
        print(f"  Day {day}: {name} ({place_id})")
    print(f"PASS: required-POI trip #{response['data']['tripId']} preserved the requested days and order; replay verified")
    print(f"Saved response: {path}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
