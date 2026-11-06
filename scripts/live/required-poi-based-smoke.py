"""Find real Google places, then generate a trip with required POIs on days 1 and 3.

Run from the repository root: python3 scripts/live/required-poi-based-smoke.py
"""

from itinerary_smoke_common import START_DATE, SmokeClient, save_response


def main():
    client = SmokeClient()
    token = client.register_and_login()
    selected = client.search_pois(token)[:3]
    if len(selected) < 3:
        raise AssertionError("/pois/search returned fewer than three usable Google places")
    required_ids = [poi["googlePlaceId"] for poi in selected]
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
    for day, poi in ((1, selected[0]), (1, selected[1]), (3, selected[2])):
        print(f"  Day {day}: {poi.get('name')} ({poi['googlePlaceId']})")
    print(f"PASS: required-POI trip #{response['data']['tripId']} preserved the requested days and order")
    print(f"Saved response: {path}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
