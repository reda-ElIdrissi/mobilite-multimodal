"""Exercise the three real participants via their private REST endpoints."""
import http.client
import io
import json
import runpy
import subprocess
import time
import uuid
from pathlib import Path
from urllib.parse import quote

support = runpy.run_path(str(Path(__file__).with_name("verify-lot2.py")))
BASE = "http://127.0.0.1:8080"
ACTOR = "lot5-smoke"


def new_id():
    return str(uuid.uuid4())


def compose(*args):
    subprocess.run(["docker", "compose", "--profile", "app", *args], check=True, timeout=60)


def call(service, token, path, *, body=None, expected=200):
    payload = json.dumps(body).encode() if body is not None else b""
    method = "POST" if body is not None else "GET"
    message = (f"{method} /internal/v1/{path} HTTP/1.1\r\nHost: {service}:8080\r\n"
               f"Authorization: Bearer {token}\r\nContent-Type: application/json\r\n"
               f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode() + payload
    script = f"exec 3<>/dev/tcp/{service}/8080; cat >&3; cat <&3"
    output = subprocess.run(["docker", "compose", "--profile", "app", "exec", "-T", "api-gateway", "bash", "-ec", script],
                            input=message, capture_output=True, check=True, timeout=25).stdout

    class Socket:
        def makefile(self, *_):
            return io.BytesIO(output)

    with http.client.HTTPResponse(Socket()) as response:
        response.begin()
        content = response.read()
        assert response.status == expected, (service, path, response.status, expected, content)
        return json.loads(content) if content else None


def offers(origin, destination, mode):
    response, _ = support["request"](BASE + "/api/v1/search", "POST", {
        "origin": origin, "destination": destination, "date": "2026-11-15", "modes": [mode],
    })
    assert not response["partial"], response
    return response["offers"]


def read_fixture(fixture):
    origin, destination, mode, identifier = fixture
    return next((o for o in offers(origin, destination, mode) if identifier is None or o["offerId"] == identifier), None)


def require_fixture(fixture):
    entry = read_fixture(fixture)
    assert entry is not None, "Missing fixture"
    return entry


def hold_body(entry):
    car = entry["operator"] == "CAR"
    return {"operationId": new_id(), "reservationLineId": new_id(), "offerId": entry["offerId"],
            "actorUserId": ACTOR, **({"car": {
                "origin": entry["origin"]["cityCode"], "destination": entry["destination"]["cityCode"],
                "startDate": entry["startDate"], "endDate": entry["endDate"], "passengerCount": 2,
                "driverAlias": "p1", "expectedDailyRateMinor": entry["unitPriceMinor"],
                "expectedPriceVersion": entry["priceVersion"]}} if car else {"seats": 1})}


def cancel(service, token, line, reference, kind):
    body = {"operationId": new_id(), "reservationLineId": line, "reference": reference, "referenceKind": kind}
    result = call(service, token, "cancellations", body=body)
    assert result["status"] == "CANCELLED"
    assert call(service, token, "cancellations", body=body) == result


def await_probe(probe):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            return probe()
        except (AssertionError, subprocess.CalledProcessError, OSError, http.client.HTTPException):
            time.sleep(1)
    raise RuntimeError("Participant did not become ready")


def verify_participant(service, token, read_token, fixture, restart=False):
    entry = await_probe(lambda: require_fixture(fixture))
    command = hold_body(entry)
    call(service, read_token, "holds", body=command, expected=403)
    held = call(service, token, "holds", body=command, expected=201)
    line = command["reservationLineId"]
    if restart:
        compose("restart", service)
    replay = await_probe(lambda: call(service, token, "holds", body=command, expected=201))
    assert held == replay
    assert call(service, token, "operations/" + command["operationId"])["state"] == "SUCCEEDED"
    assert call(service, token, "holds", body={**command, "actorUserId": "other"}, expected=409)["code"] == "OPERATION_ID_CONFLICT"
    assert call(service, token, "holds", body={**command, "operationId": new_id()}, expected=409)["code"] == "LINE_OPERATION_CONFLICT"
    active = read_fixture(fixture)
    assert active is None if entry["operator"] == "CAR" else active["capacity"] == entry["capacity"] - 1
    path = "holds/" + quote(held["holdId"], safe="") + "/confirm"
    assert call(service, token, path, body={"operationId": new_id(), "reservationLineId": new_id()},
                expected=409)["code"] == "REFERENCE_NOT_OWNED"
    confirm = {"operationId": new_id(), "reservationLineId": line}
    booking = call(service, token, path, body=confirm)
    assert call(service, token, path, body=confirm) == booking
    cancel(service, token, line, booking["bookingRef"], "BOOKING")
    assert read_fixture(fixture)["capacity"] == entry["capacity"]

    if entry["operator"] == "CAR":
        bad_price = hold_body(entry)
        bad_price["car"]["expectedDailyRateMinor"] += 1
        assert call(service, token, "holds", body=bad_price, expected=409)["code"] == "PRICE_CHANGED"
    hold_to_cancel = hold_body(entry)
    fresh = call(service, token, "holds", body=hold_to_cancel, expected=201)
    cancel(service, token, hold_to_cancel["reservationLineId"], fresh["holdId"], "HOLD")
    assert read_fixture(fixture)["capacity"] == entry["capacity"]
    print(service + " holds, replays, cancellation and inventory OK")


def verify_air_unknown(token, fixture):
    original = read_fixture(fixture)
    rejected = hold_body(original)
    rejected["seats"] = original["capacity"] + 1
    assert call("air-bridge", token, "holds", body=rejected, expected=409)["code"] == "NOT_AVAILABLE"
    attempt = hold_body(original)
    compose("stop", "air")
    try:
        assert call("air-bridge", token, "holds", body=attempt, expected=503)["outcome"] == "UNKNOWN"
    finally:
        compose("start", "air")
    await_probe(lambda: require_fixture(fixture))
    assert call("air-bridge", token, "holds", body=attempt, expected=503)["outcome"] == "UNKNOWN"
    assert call("air-bridge", token, "operations/" + attempt["operationId"])["state"] == "UNKNOWN"
    assert read_fixture(fixture)["capacity"] == original["capacity"]


if __name__ == "__main__":
    admin = support["admin_token"]()
    token = support["service_token"](admin, "reservation-service")
    read_token = support["service_token"](admin)
    rail = ("CAS", "RAB", "TRAIN", "RAIL-CAS-RAB-0730")
    air = ("RAB", "PAR", "AVION", "AIR-RBA-ORY-1200")
    car = ("PAR", "VERSAILLES", "VOITURE", None)
    verify_participant("rail-bridge", token, read_token, rail, restart=True)
    verify_participant("air-bridge", token, read_token, air)
    verify_participant("autopartage", token, read_token, car)
    verify_air_unknown(token, air)
    print("Lot 5 OK: real operators and no replay after an uncertain SOAP hold.")
