"""Verify direct search against the actual local Compose services."""
import json
from datetime import datetime, timedelta
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

URL = "http://127.0.0.1:8080/api/v1/search"
DATE = "2026-11-15"


def query(origin, destination, expected=200, **fields):
    request = Request(URL, json.dumps({"origin": origin, "destination": destination, "date": DATE, **fields}).encode(),
                      {"Content-Type": "application/json"})
    started = time.monotonic()
    try:
        response = urlopen(request, timeout=12)
    except HTTPError as error:
        response = error
    with response:
        data = json.load(response)
        assert response.status == expected, (response.status, expected, data)
        return data, time.monotonic() - started


def find(data, offer_id):
    assert not data["partial"] and not data["errors"], data
    return next(offer for offer in data["offers"] if offer["offerId"] == offer_id)


def await_air():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            batch, _ = query("RAB", "PAR")
            if not batch["partial"] and any(offer["operator"] == "AIR" for offer in batch["offers"]):
                return batch
        except (AssertionError, OSError, URLError):
            pass
        time.sleep(2)
    raise RuntimeError("Search stack did not become ready")


def compose(*args):
    subprocess.run(["docker", "compose", "--profile", "app", *args], check=True, timeout=60)


def main():
    air = find(await_air(), "AIR-RBA-ORY-1200")
    assert (air["unitPriceMinor"], air["mode"], air["capacity"]) == (180000, "AVION", 40)
    assert datetime.fromisoformat(air["arrivalAt"]).isoformat() == "2026-11-15T15:10:00+01:00", air
    dakar = find(query("CAS", "DAK")[0], "AIR-CMN-DSS-1500")
    assert datetime.fromisoformat(dakar["departureAt"]).hour == 15, dakar
    assert datetime.fromisoformat(dakar["arrivalAt"]).utcoffset() == timedelta(0), dakar

    rail = find(query("CAS", "RAB")[0], "RAIL-CAS-RAB-0730")
    assert (rail["unitPriceMinor"], rail["mode"], rail["capacity"]) == (8000, "TRAIN", 120)
    rail_arrival = datetime.fromisoformat(rail["arrivalAt"])
    assert (rail_arrival.hour, rail_arrival.minute) == (8, 25) and rail_arrival.utcoffset() is not None, rail
    accented = find(query("CAS", "FES")[0], "RAIL-CAS-FES-0815")
    assert accented["destination"]["displayName"] == "Fès"
    tomorrow = find(query("CAS", "RAB", date="2026-11-16")[0], "RAIL-CAS-RAB-0730-16")
    assert tomorrow["arrivalAt"].startswith("2026-11-16")

    car_data, _ = query("PAR", "VERSAILLES", dateFin="2026-11-16", travelerCount=5, modes=["VOITURE"])
    assert not car_data["partial"] and len(car_data["offers"]) == 1, car_data
    car = car_data["offers"][0]
    assert (car["operator"], car["mode"], car["unitPriceMinor"], car["unitType"], car["capacity"]) == (
        "CAR", "VOITURE", 30000, "DAY", 4)
    assert car["startDate"] == DATE and car["endDate"] == "2026-11-16"
    assert car.get("arrivalAt") is None and "immatriculation" not in car
    no_offers, _ = query("RAB", "PAR", travelerCount=41, modes=["AVION"])
    assert no_offers["offers"] == [] and not no_offers["partial"]
    query("CAS", "???", expected=400)
    query("PAR", "VERSAILLES", expected=400, dateFin="2026-12-17")

    compose("stop", "air")
    try:
        partial, elapsed = query("CAS", "RAB")
        assert elapsed < 10 and partial["partial"] and set(partial["errors"]) == {"AIR"}, partial
        assert any(offer["operator"] == "RAIL" for offer in partial["offers"]), partial
        car_partial, _ = query("PAR", "VERSAILLES")
        assert set(car_partial["errors"]) == {"AIR"}
        assert any(offer["operator"] == "CAR" for offer in car_partial["offers"])
        outage, _ = query("RAB", "PAR", expected=503, modes=["AVION"])
        assert outage["partial"] and outage["offers"] == [] and set(outage["errors"]) == {"AIR"}
    finally:
        compose("start", "air")
    await_air()
    print(f"Lot 3 OK: trois modes, horaires zonés, erreurs partielles, HTTP 503 et reprise. Panne air: {elapsed:.2f} s.")


if __name__ == "__main__":
    main()
