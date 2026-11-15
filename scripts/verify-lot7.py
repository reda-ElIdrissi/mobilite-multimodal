"""Verify real RabbitMQ, outbox recovery, notifications and quote reconciliation."""
import base64
import http.client
import io
import json
from pathlib import Path
import runpy
import secrets
import subprocess
import time
from urllib.parse import quote
import uuid

scenario = runpy.run_path(str(Path(__file__).with_name("verify-lot6.py")))
common = scenario["common"]
request = common["request"]
root = scenario["root"]
compose_base = ["docker", "compose", "--profile", "app"]
pricing_dlq = "pricing.reservation-results.dlq"
notification_dlq = "notification.reservation-results.dlq"


def compose(*args, capture=False):
    result = subprocess.run([*compose_base, *args], check=True, capture_output=capture, timeout=25)
    return result.stdout if capture else None


def wait_for(action, timeout=75):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = action()
            if result:
                return result
        except (AssertionError, OSError, subprocess.CalledProcessError, http.client.HTTPException):
            pass
        time.sleep(0.7)
    raise AssertionError(f"Condition not met within {timeout} seconds")


def sql(query):
    return compose("exec", "-T", "reservation-db", "psql", "-U", "reservation", "-d", "reservation",
                   "-Atc", query, capture=True).decode().strip()


def broker_credentials():
    user = compose("exec", "-T", "rabbitmq", "printenv", "RABBITMQ_DEFAULT_USER", capture=True).strip()
    password = compose("exec", "-T", "rabbitmq", "printenv", "RABBITMQ_DEFAULT_PASS", capture=True).strip()
    return base64.b64encode(user + b":" + password).decode()


def broker(path, authorization, body=None):
    payload = json.dumps(body).encode() if body is not None else b""
    method = "POST" if body is not None else "GET"
    raw = (f"{method} {path} HTTP/1.1\r\nHost: rabbitmq:15672\r\nAuthorization: Basic {authorization}\r\n"
           f"Content-Type: application/json\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode() + payload
    script = "exec 3<>/dev/tcp/rabbitmq/15672; cat >&3; cat <&3"
    completed = subprocess.run([*compose_base, "exec", "-T", "api-gateway", "bash", "-ec", script],
                               input=raw, capture_output=True, check=True, timeout=15)

    class Socket:
        def makefile(self, *_):
            return io.BytesIO(completed.stdout)

    with http.client.HTTPResponse(Socket()) as response:
        response.begin()
        assert response.status == 200, response.status
        return json.load(response)


def publish(key, payload, authorization):
    result = broker("/api/exchanges/%2F/mobility.events/publish", authorization,
                    {"routing_key": key, "payload_encoding": "string", "payload": payload,
                     "properties": {"content_type": "application/json", "delivery_mode": 2}})
    assert result["routed"]


def dead_letters(queue, authorization):
    return broker("/api/queues/%2F/" + quote(queue, safe=""), authorization).get("messages", 0)


def main():
    admin = common["admin_token"]()
    customer, _ = request(common["ADMIN"] + "/roles/CUSTOMER", token=admin)
    users = []
    broker_stopped = notification_stopped = False
    try:
        tokens = []
        for _ in range(2):
            username = "events-smoke-" + secrets.token_hex(5)
            password = secrets.token_urlsafe(20)
            users.append(common["create_user"](admin, username, password, [customer]))
            tokens.append(common["login"]("mobility-web", username, password))
        owner, outsider = tokens
        request(root + "/notifications", expected=401)
        request(root + "/notifications", token=owner)
        selection, _ = scenario["booking_selection"]()
        quote_result, _ = request(root + "/quotes", "POST", selection, token=owner, expected=201)
        quote_url = root + "/quotes/" + quote_result["id"]
        broker_stopped = notification_stopped = True
        compose("stop", "notification", "rabbitmq")
        response, headers = request(root + "/reservations", "POST", {"devisId":quote_result["id"]},
                                     token=owner, headers={"Idempotency-Key":str(uuid.uuid4())}, expected=202)
        target = "http://127.0.0.1:8080" + headers["Location"]
        reservation_id = str(uuid.UUID(response["id"]))
        scenario["wait_status"](target, owner, "CONFIRMED")
        assert int(sql(f"SELECT count(*) FROM outbox_event WHERE reservation_id='{reservation_id}' AND published_at IS NULL")) > 0
        wait_for(lambda: request(quote_url, token=owner)[0]["status"] == "CONSUMED",timeout=70)
        print("Scanner and quote reconciliation work while RabbitMQ and notification are offline.")

        compose("start", "rabbitmq")
        broker_stopped = False
        wait_for(lambda: int(sql(f"SELECT count(*) FROM outbox_event WHERE reservation_id='{reservation_id}' AND published_at IS NULL")) == 0)
        request(target + "/cancel","POST",{},token=owner,headers={"Idempotency-Key":str(uuid.uuid4())},expected=202)
        scenario["wait_status"](target, owner, "CANCELLED")

        def event(key):
            return sql(f"SELECT payload FROM outbox_event WHERE reservation_id='{reservation_id}' AND event_type='{key}' AND published_at IS NOT NULL")
        cancellation = wait_for(lambda: event("reservation.cancelled.v1"))
        confirmation = wait_for(lambda: event("reservation.confirmed.v1"))
        authorization = broker_credentials()
        baseline = {q:dead_letters(q,authorization) for q in (pricing_dlq,notification_dlq)}
        publish("reservation.cancelled.v1",cancellation,authorization)
        stale = json.loads(confirmation)
        stale["eventId"] = str(uuid.uuid4())
        publish("reservation.confirmed.v1",json.dumps(stale),authorization)
        publish("reservation.confirmed.v1","not a valid event",authorization)

        compose("start","notification")
        notification_stopped = False

        def read_notifications():
            values,_=request(root + "/notifications",token=owner)
            return [row for row in values if row["reservationId"]==reservation_id]

        ids = wait_for(lambda: (values if len(values := read_notifications())>=2 else None))
        assert {row["type"] for row in ids} == {"CONFIRMED","CANCELLED"} and len(ids)==2
        assert [row["type"] for row in ids if row["current"]] == ["CANCELLED"]
        for queue_id,count in baseline.items():
            wait_for(lambda: dead_letters(queue_id,authorization)>count)
        assert len(read_notifications()) == 2
        assert request(quote_url,token=owner)[0]["status"]=="CONSUMED"
        item = next(row for row in ids if row["current"])
        url = root + "/notifications/" + item["id"] + "/read"
        request(url,"PATCH",token=outsider,expected=404)
        request(url,"PATCH",token=owner,expected=204)
        read_at = next(row["readAt"] for row in read_notifications() if row["id"]==item["id"])
        request(url,"PATCH",token=owner,expected=204)
        assert read_at and next(row["readAt"] for row in read_notifications() if row["id"]==item["id"])==read_at
        print("Lot 7 OK: outbox recovery, quote reconciliation, stale/duplicate events, poison DLQs and notification ownership.")
    finally:
        if broker_stopped:
            compose("start","rabbitmq")
        if notification_stopped:
            compose("start","notification")
        admin = common["admin_token"]()
        for user in users:
            request(common["ADMIN"] + "/users/" + user,"DELETE",token=admin,expected=204)


if __name__ == "__main__":
    main()
