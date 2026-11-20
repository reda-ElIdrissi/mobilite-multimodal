"""Run real T10/T11 against an isolated stack started with compose-lot9.yml."""
from datetime import datetime
import json
from pathlib import Path
import runpy
import secrets
import time
from urllib.error import HTTPError
from urllib.request import urlopen
import uuid

HERE = Path(__file__).parent
booking = runpy.run_path(str(HERE / "verify-lot6.py"))
ops = runpy.run_path(str(HERE / "verify-lot5.py"))
common = booking["common"]
pricing = booking["pricing"]
request = common["request"]
root = booking["root"]
barrier = HERE.parent / ".runtime" / "lot9-barrier"


def until(probe, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(0.2)
    raise AssertionError("Timed out waiting for " + probe.__name__)


def submit(selection, token):
    quote, _ = request(root + "/quotes", "POST", selection, token=token, expected=201)
    trace = uuid.uuid4().hex
    item, _ = request(root + "/reservations", "POST", {"devisId": quote["id"]}, token=token, expected=202,
                      headers={"Idempotency-Key": str(uuid.uuid4()), "traceparent": f"00-{trace}-{secrets.token_hex(8)}-01"})
    return item["id"], quote["id"], trace


def steps(identifier, token):
    return request(root + f"/admin/reservations/{identifier}/operations", token=token)[0]


def terminal(identifier, quote, token, code):
    item = booking["wait_status"](root + "/reservations/" + identifier, token, "FAILED_COMPENSATED")
    assert item["failureCode"] == code, item
    until(lambda: request(root + "/quotes/" + quote, token=token)[0]["status"] == "VOID")


def t10(user, service):
    fixtures = [("RAK", "CAS", "AVION", "AIR-RAK-CMN-0930"), ("CAS", "MAD", "AVION", "AIR-CMN-MAD-1800")]
    first, second = (ops["require_fixture"](fixture) for fixture in fixtures)
    assert [first["capacity"], second["capacity"]] == [70, 3]
    selection = {"travelerCount": 3, "participants": ["p1", "p2", "p3"], "segments": [
        pricing["segment"](i, offer, offer["departureAt"], offer["arrivalAt"])
        for i, offer in enumerate((first, second))]}
    for segment in selection["segments"]:
        segment["allocations"][0].update(passengerCount=3, participantAliases=selection["participants"])
    identifier, quote, trace = submit(selection, user)
    ready = barrier / "t10.ready"
    release = barrier / "t10.release"
    command = {**ops["hold_body"](second), "seats": 3}
    held = None
    try:
        until(lambda: ready.exists() and ready.read_text().strip() == identifier, 40)
        held = ops["call"]("air-bridge", service, "holds", body=command, expected=201)
        assert ops["read_fixture"](fixtures[1]) is None
        release.write_text(identifier)
        terminal(identifier, quote, user, "NOT_AVAILABLE")
        journal = steps(identifier, user)
        assert not any(s["action"] == "CONFIRM" for s in journal)
        successful = next(s for s in journal if s["action"] == "HOLD" and s["state"] == "SUCCEEDED")
        assert any(s["action"] == "CANCEL" and s["state"] == "SUCCEEDED"
                   and s["reference"] == "HOLD:" + successful["result"]["reference"] for s in journal)
        assert ops["require_fixture"](fixtures[0])["capacity"] == 70
        assert ops["read_fixture"](fixtures[1]) is None
    finally:
        release.write_text(identifier)
        if held:
            ops["cancel"]("air-bridge", service, command["reservationLineId"], held["holdId"], "HOLD")
    assert ops["require_fixture"](fixtures[1])["capacity"] == 3
    print("T10 OK", identifier, trace)


def t11(user):
    selection, initial = booking["booking_selection"]()
    identifier, quote, trace = submit(selection, user)

    def sent():
        journal = steps(identifier, user)
        confirmed = [s for s in journal if s["action"] == "CONFIRM" and s["state"] == "SUCCEEDED"]
        active = [s for s in journal if s["action"] == "CONFIRM" and s["state"] == "IN_FLIGHT"]
        if len(confirmed) == 2 and active:
            started = active[0]
            hold = next(s for s in journal if s["action"] == "HOLD" and s["line"] == started["line"])
            return started, hold, confirmed
        return None

    started, hold, confirmed = until(sent)
    expiry = datetime.fromisoformat(hold["result"]["expires"])
    assert (expiry - datetime.fromisoformat(started["updated"])).total_seconds() > 5
    terminal(identifier, quote, user, "HOLD_EXPIRED")
    journal = steps(identifier, user)
    rejection = next(s for s in journal if s["id"] == started["id"])
    assert rejection["state"] == "REJECTED" and rejection["result"]["code"] == "HOLD_EXPIRED"
    assert datetime.fromisoformat(rejection["updated"]) >= expiry
    released = {s["reference"] for s in journal if s["action"] == "CANCEL" and s["state"] == "SUCCEEDED"}
    assert released == {"HOLD:" + hold["result"]["reference"],
                        *("BOOKING:" + s["result"]["reference"] for s in confirmed)}
    assert booking["booking_selection"]()[1] == initial
    print("T11 OK", identifier, trace, "sent", started["updated"], "expiry", expiry.isoformat(),
          "rejected", rejection["updated"])
    return trace, identifier


def trace_ready(trace, identifier):
    try:
        with urlopen("http://127.0.0.1:16686/api/traces/" + trace, timeout=4) as response:
            data = json.load(response)["data"]
    except HTTPError:
        return False
    if not data:
        return False
    spans = data[0]["spans"]
    names = {s["operationName"] for s in spans}
    services = {p["serviceName"] for p in data[0]["processes"].values()}
    return ({"legacy.iiop.cancel", "legacy.soap.CancelFlight", "outbox.publish", "saga.confirm"} <= names
            and {"api-gateway", "reservation", "rail-bridge", "air-bridge", "autopartage"} <= services
            and any("saga" in name and ("publish" in name or "process" in name or "receive" in name) for name in names)
            and any(tag["key"] == "saga.id" and tag["value"] == identifier for s in spans for tag in s["tags"]))


def main():
    barrier.mkdir(parents=True, exist_ok=True)
    admin = common["admin_token"]()
    roles = [request(common["ADMIN"] + "/roles/" + role, token=admin)[0] for role in ("CUSTOMER", "SUPPORT_ADMIN")]
    username = "demo-saga-" + secrets.token_hex(5)
    password = secrets.token_urlsafe(20)
    user = common["create_user"](admin, username, password, roles)
    try:
        token = common["login"]("mobility-web", username, password)
        t10(token, common["service_token"](admin, "reservation-service"))
        trace, identifier = t11(token)
        until(lambda: trace_ready(trace, identifier), 40)
        print("Jaeger OK", trace)
    finally:
        request(common["ADMIN"] + "/users/" + user, "DELETE", token=common["admin_token"](), expected=204)


if __name__ == "__main__":
    main()
