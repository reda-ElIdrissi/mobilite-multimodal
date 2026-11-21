"""Replay the public booking flows with the local Keycloak realm."""
import os
from pathlib import Path
import runpy
import secrets
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
booking = runpy.run_path(str(HERE / "verify-lot6.py"))
recovery = runpy.run_path(str(HERE / "verify-lot9.py"))
common = booking["common"]
request = common["request"]
root = booking["root"]


def demo_env(name):
    return os.environ.get(name) or subprocess.check_output(
        ["docker", "compose", "-f", "docker-compose.yml", "exec", "-T", "keycloak", "printenv", name],
        text=True, timeout=10,
    ).strip()


def ready_selection():
    deadline = time.monotonic() + 45
    while True:
        try:
            return booking["booking_selection"]()
        except (AssertionError, OSError, StopIteration):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


def nominal(token):
    selection, initial = ready_selection()
    identifier, quote, trace = recovery["submit"](selection, token)
    endpoint = root + "/reservations/" + identifier
    try:
        result = booking["wait_status"](endpoint, token, "CONFIRMED")
        assert [line["allocation"]["mode"] for line in result["lines"]] == ["TRAIN", "AVION", "VOITURE"]
        assert all(line["bookingRef"] for line in result["lines"])
        print(f"CONFIRMED dossier={identifier} devis={quote} trace={trace}", flush=True)
    finally:
        request(endpoint + "/cancel", "POST", {}, token=token, expected=202,
                headers={"Idempotency-Key": str(uuid.uuid4())})
        booking["wait_status"](endpoint, token, "CANCELLED")
    assert booking["booking_selection"]()[1] == initial
    print(f"CANCELLED dossier={identifier}, disponibilités rétablies")


def main(mode):
    if mode == "token":
        print(common["login"]("mobility-web", "demo-traveler", demo_env("MOBILITY_DEMO_TRAVELER_PASSWORD")))
        return

    if not os.environ.get("MOBILITY_ADMIN_PASSWORD"):
        os.environ["MOBILITY_ADMIN_PASSWORD"] = demo_env("KC_BOOTSTRAP_ADMIN_PASSWORD")
    admin = common["admin_token"]()
    roles = ["CUSTOMER"] if mode == "nominal" else ["CUSTOMER", "SUPPORT_ADMIN"]
    realm_roles = [request(common["ADMIN"] + "/roles/" + role, token=admin)[0] for role in roles]
    login = "demo-booking-" + secrets.token_hex(5)
    password = secrets.token_urlsafe(20)
    user = common["create_user"](admin, login, password, realm_roles)
    try:
        token = common["login"]("mobility-web", login, password)
        if mode == "nominal":
            nominal(token)
        else:
            ready_selection()
            _, identifier = recovery["t11"](token)
            print(f"FAILED_COMPENSATED dossier={identifier}, disponibilités rétablies")
    finally:
        request(common["ADMIN"] + "/users/" + user, "DELETE", token=common["admin_token"](), expected=204)


if __name__ == "__main__":
    main(sys.argv[1])
