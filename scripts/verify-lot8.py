"""Check composition, real batch pricing and booking on the disposable Compose stack."""
from copy import deepcopy
from datetime import datetime, timedelta
from pathlib import Path
import runpy
import secrets
import time
import uuid
from urllib.error import URLError

HERE = Path(__file__).parent
common = runpy.run_path(str(HERE / "verify-lot2.py"))
search = runpy.run_path(str(HERE / "verify-lot3.py"))
booking = runpy.run_path(str(HERE / "verify-lot6.py"))
request = common["request"]
query = search["query"]
compose = search["compose"]
root = booking["root"]
wait_status = booking["wait_status"]


def assert_priced(batch):
    assert not batch["partial"] and not batch["errors"] and batch.get("pricingError") is None, batch
    assert batch["itineraries"] and batch["assumptions"], batch
    def ranking(candidate):
        legs = candidate["selection"]["segments"]
        elapsed = datetime.fromisoformat(legs[-1]["plannedArrival"]) - datetime.fromisoformat(legs[0]["plannedDeparture"])
        return candidate["estimatedTotal"]["amountMinor"], elapsed
    ranks = [ranking(candidate) for candidate in batch["itineraries"]]
    assert ranks == sorted(ranks), ranks


def issue(candidate, token):
    quote, _ = request(root + "/quotes", "POST", candidate["selection"], token=token, expected=201)
    assert quote["price"]["total"] == candidate["estimatedTotal"], quote
    return quote


def main():
    admin = common["admin_token"]()
    login = "composition-" + secrets.token_hex(5)
    password = secrets.token_urlsafe(20)
    roles = [request(common["ADMIN"] + "/roles/" + name, token=admin)[0] for name in ("OWNER", "CUSTOMER")]
    user = common["create_user"](admin, login, password, roles)
    vehicle_url = None
    reservation_url = None
    token = None
    try:
        token = common["login"]("mobility-web", login, password)
        direct = dict(maxSegments=1, modes=["VOITURE"], dateFin="2026-11-16")
        batch, _ = query("PAR", "VERSAILLES", travelerCount=2, driverCount=1, **direct)
        assert_priced(batch)
        single = next(c for c in batch["itineraries"]
                      if c["selection"]["segments"][0]["allocations"][0]["startDate"] == search["DATE"])
        assert single["availability"][0]["capacity"] == 4
        quote = issue(single, token)
        assert quote["price"]["itinerary"]["segments"][0]["allocations"][0]["quantity"] == 2

        grouped = dict(maxSegments=3, via=["RAB", "PAR"], travelerCount=2, dateFin="2026-11-16")
        batch, _ = query("CAS", "VERSAILLES", **grouped)
        assert_priced(batch)
        assert not batch["truncated"]
        expected = ["RAIL-CAS-RAB-0730", "AIR-RBA-ORY-1200"]
        route = next(c for c in batch["itineraries"]
                     if [a["offerId"] for a in c["availability"][:2]] == expected
                     and c["selection"]["segments"][2]["allocations"][0]["startDate"] == search["DATE"])
        assert [a["operator"] for a in route["availability"]] == ["RAIL", "AIR", "CAR"], route
        issue(route, token)
        invalid = deepcopy(route["selection"])
        legs = invalid["segments"]
        too_early = datetime.fromisoformat(legs[1]["plannedArrival"]) + timedelta(minutes=29)
        legs[2]["plannedDeparture"] = too_early.isoformat()
        request(root + "/quotes", "POST", invalid, token=token, expected=400)
        inferred, _ = query("CAS", "VERSAILLES", maxSegments=3, travelerCount=2, dateFin="2026-11-16")
        assert_priced(inferred)
        assert inferred["truncated"]
        assert any([a["offerId"] for a in c["availability"][:2]] == expected for c in inferred["itineraries"])
        query("CAS", "VERSAILLES", expected=400, maxSegments=2, via=["RAB", "PAR"])

        vehicle, _ = request(root + "/vehicules", "POST", {
            "marque": "Renault", "modele": "Test composition", "immatriculation": "LOT8-" + secrets.token_hex(5),
            "couleur": "Bleu", "ville": "Paris", "categorie": "Citadine", "dailyRateMinor": 30000,
            "passengerCapacity": 4,
        }, token=token, expected=201)
        vehicle_url = root + "/vehicules/" + str(vehicle["id"])
        request(vehicle_url + "/destinations", "PUT", {"permissions": [
            {"destination": "Versailles", "debut": search["DATE"], "fin": "2026-11-16"}
        ]}, token=token)
        request(vehicle_url + "/enable", "PATCH", token=token)
        impossible, _ = query("PAR", "VERSAILLES", travelerCount=5, driverCount=1, **direct)
        assert not impossible["partial"] and not impossible["itineraries"], impossible
        parallel, _ = query("PAR", "VERSAILLES", travelerCount=5, driverCount=2, **direct)
        assert_priced(parallel)
        candidate = next(c for c in parallel["itineraries"]
                         if c["selection"]["segments"][0]["allocations"][0]["startDate"] == search["DATE"])
        allocations = candidate["selection"]["segments"][0]["allocations"]
        assert len(allocations) == 2 and sum(a["passengerCount"] for a in allocations) == 5
        assert {a["driverAlias"] for a in allocations} == {"p1", "p2"}
        quote = issue(candidate, token)
        amounts = quote["price"]["itinerary"]["segments"][0]["allocations"]
        assert [a["quantity"] for a in amounts] == [2, 2]
        mixed, _ = query("CAS", "VERSAILLES", **(grouped | {"travelerCount": 5, "driverCount": 2}))
        assert_priced(mixed)
        mixed_candidate = next(c for c in mixed["itineraries"]
                               if [a["offerId"] for a in c["availability"][:2]] == expected
                               and c["selection"]["segments"][2]["allocations"][0]["startDate"] == search["DATE"])
        assert [a["operator"] for a in mixed_candidate["availability"]] == ["RAIL", "AIR", "CAR", "CAR"]
        mixed_quote = issue(mixed_candidate, token)
        _, headers = request(root + "/reservations", "POST", {"devisId": mixed_quote["id"]}, token=token,
                             headers={"Idempotency-Key": str(uuid.uuid4())}, expected=202)
        reservation_url = common["GATEWAY"] + headers["Location"]
        confirmed = wait_status(reservation_url, token, "CONFIRMED")
        assert [line["allocation"]["mode"] for line in confirmed["lines"]] == ["TRAIN", "AVION", "VOITURE", "VOITURE"]
        assert all(line["bookingRef"] and line["state"] == "CONFIRMED" for line in confirmed["lines"])
        assert len({line["bookingRef"] for line in confirmed["lines"][2:]}) == 2
        cancel(reservation_url, token)
        reservation_url = None

        compose("stop", "tarification")
        try:
            degraded, elapsed = query("PAR", "VERSAILLES", travelerCount=5, driverCount=2, **direct)
            assert elapsed < 12 and degraded["partial"] and not degraded["errors"], degraded
            assert degraded["pricingError"] == "PRICING_UNAVAILABLE" and degraded["itineraries"], degraded
            assert all(c.get("estimatedTotal") is None for c in degraded["itineraries"])
        finally:
            compose("start", "tarification")
        await_pricing(direct)
        print("Lot 8 OK: batch ranking, direct car, inferred and explicit three-mode route, parallel booking and release, pricing outage and recovery.")
    finally:
        if reservation_url:
            cancel(reservation_url, token)
        if vehicle_url:
            request(vehicle_url + "/disable", "PATCH", token=token)
        request(common["ADMIN"] + "/users/" + user, "DELETE", token=common["admin_token"](), expected=204)


def cancel(url, token):
    request(url + "/cancel", "POST", {}, token=token, headers={"Idempotency-Key": str(uuid.uuid4())}, expected=202)
    wait_status(url, token, "CANCELLED")


def await_pricing(direct):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            data, _ = query("PAR", "VERSAILLES", travelerCount=2, **direct)
            assert_priced(data)
            return
        except (AssertionError, OSError, URLError):
            time.sleep(2)
    raise AssertionError("Pricing did not recover")


if __name__ == "__main__":
    main()
