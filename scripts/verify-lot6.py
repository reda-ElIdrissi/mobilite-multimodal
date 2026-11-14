"""Exercise the real three-mode saga, idempotent ingress and compensation by cancellation."""
from concurrent.futures import ThreadPoolExecutor
import runpy
import secrets
import subprocess
import time
import uuid
from pathlib import Path

common = runpy.run_path(str(Path(__file__).with_name("verify-lot2.py")))
pricing = runpy.run_path(str(Path(__file__).with_name("verify-lot4.py")))
request = common["request"]
root = "http://127.0.0.1:8080/api/v1"
admin_url = common["ADMIN"]


def wait_status(url, token, expected):
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        state, _ = request(url, token=token)
        if state["status"] == expected:
            return state
        if state["status"] in {"NEEDS_RECONCILIATION", "FAILED_COMPENSATED", "CANCELLED"}:
            raise AssertionError(state)
        time.sleep(0.5)
    raise AssertionError(("timeout", expected, state))


def booking_selection():
    train = pricing["offer"]("CAS", "RAB", "TRAIN", "RAIL-CAS-RAB-0730")
    air = pricing["offer"]("RAB", "PAR", "AVION", "AIR-RBA-ORY-1200")
    car = pricing["offer"]("PAR", "VERSAILLES", "VOITURE")
    segment = pricing["segment"]
    return {"travelerCount": 2, "participants": ["p1", "p2"], "promoCode": "DEMO", "segments": [
        segment(0, train, train["departureAt"], train["arrivalAt"]),
        segment(1, air, air["departureAt"], air["arrivalAt"]),
        segment(2, car, "2026-11-15T17:00:00+01:00", "2026-11-16T12:00:00+01:00",
                startDate="2026-11-15", endDate="2026-11-16", driverAlias="p1"),
    ]}, [train["capacity"], air["capacity"], car["capacity"]]


def published(reservation_id):
    query = """SELECT event_type FROM outbox_event WHERE reservation_id='%s'
               AND published_at IS NOT NULL ORDER BY created_at""" % uuid.UUID(reservation_id)
    args = ["docker", "compose", "--profile", "app", "exec", "-T", "reservation-db",
            "psql", "-U", "reservation", "-d", "reservation", "-Atc", query]
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        rows = subprocess.run(args, check=True, capture_output=True, text=True, timeout=10).stdout.splitlines()
        if {"saga.requested.v1", "reservation.confirmed.v1", "reservation.cancelled.v1"}.issubset(rows):
            return rows
        time.sleep(0.5)
    raise AssertionError(("Expected published events", rows))


def main():
    admin = common["admin_token"]()
    customer, _ = request(admin_url + "/roles/CUSTOMER", token=admin)
    users = []
    try:
        tokens = []
        for _ in range(2):
            login = "saga-smoke-" + secrets.token_hex(5)
            password = secrets.token_urlsafe(20)
            users.append(common["create_user"](admin, login, password, [customer]))
            tokens.append(common["login"]("mobility-web", login, password))
        owner, outsider = tokens
        selection, inventory = booking_selection()
        quote, _ = request(root + "/quotes", "POST", selection, token=owner, expected=201)
        body = {"devisId": quote["id"]}
        key = str(uuid.uuid4())
        headers = {"Idempotency-Key": key}
        request(root + "/reservations", "POST", body, headers=headers, expected=401)
        request(root + "/reservations", "POST", body, token=outsider, headers=headers, expected=404)

        def submit():
            return request(root + "/reservations", "POST", body, token=owner, headers=headers, expected=202)
        with ThreadPoolExecutor(max_workers=2) as executor:
            first, second = list(executor.map(lambda _: submit(), range(2)))
        assert first[0]["id"] == second[0]["id"] and first[1]["Location"] == second[1]["Location"]
        url = "http://127.0.0.1:8080" + first[1]["Location"]
        request(root + "/reservations", "POST", {"devisId": str(uuid.uuid4())}, token=owner, headers=headers, expected=409)
        request(url, token=outsider, expected=404)
        confirmed = wait_status(url, owner, "CONFIRMED")
        assert confirmed["total"]["amountMinor"] == 409500, confirmed
        assert [line["allocation"]["mode"] for line in confirmed["lines"]] == ["TRAIN", "AVION", "VOITURE"]
        assert all(line["bookingRef"] and line["state"] == "CONFIRMED" for line in confirmed["lines"])

        cancellation = {"Idempotency-Key": str(uuid.uuid4())}
        request(url + "/cancel", "POST", {}, token=owner, headers=cancellation, expected=202)
        cancelled = wait_status(url, owner, "CANCELLED")
        assert all(line["state"] == "CANCELLED" for line in cancelled["lines"])
        repeated, _ = request(url + "/cancel", "POST", {}, token=owner, headers=cancellation, expected=202)
        assert repeated["id"] == cancelled["id"]
        assert booking_selection()[1] == inventory
        published(cancelled["id"])
        print("Lot 6 OK: three true operators, concurrent client replay, consolidated quote, cancellation and RabbitMQ outbox.")
    finally:
        admin = common["admin_token"]()
        for user in users:
            request(admin_url + "/users/" + user, "DELETE", token=admin, expected=204)


if __name__ == "__main__":
    main()
