"""Generate a three-day Manhattan trip with no required POIs using real Google Places.

Run from the repository root: python3 scripts/live/city-centralized-smoke.py
"""

from itinerary_smoke_common import CITY_CENTER, START_DATE, SmokeClient, save_response


def main():
    client = SmokeClient()
    token = client.register_and_login()
    request = {
        "name": "Manhattan city smoke trip",
        "startDate": START_DATE,
        "dayCount": 3,
        "searchArea": {**CITY_CENTER, "radiusMeters": 15000},
        "constraints": {"maxPoisPerDay": 3, "preferredTypes": []},
    }
    response = client.generate_and_verify(token, request)
    path = save_response("city-centralized.json", response)
    print(f"PASS: city-based trip #{response['data']['tripId']} has three saved days")
    print(f"Saved response: {path}")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, KeyError, OSError, TypeError, ValueError) as error:
        raise SystemExit(f"FAIL: {error}") from None
