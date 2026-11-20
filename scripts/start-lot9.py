"""Bootstrap the local realm and start the full Compose stack."""
import argparse
import os
from pathlib import Path
import runpy
import subprocess
import time
from urllib.error import URLError

common = runpy.run_path(str(Path(__file__).with_name("verify-lot2.py")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="Reuse the local Compose images")
    args = parser.parse_args()
    if not os.environ.get("MOBILITY_ADMIN_PASSWORD"):
        raise SystemExit("Set MOBILITY_ADMIN_PASSWORD for the local Keycloak administrator.")
    subprocess.run(["docker", "compose", "up", "-d", "keycloak"], check=True)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            admin = common["admin_token"]()
            break
        except (AssertionError, OSError, URLError):
            time.sleep(2)
    else:
        raise RuntimeError("Keycloak administrator unavailable")
    for key, client in (("BFF", "search-bff"), ("PRICING", "pricing-service"), ("RESERVATION", "reservation-service")):
        os.environ["MOBILITY_" + key + "_SECRET"] = common["service_secret"](admin, client)
    subprocess.run(["docker", "compose", "up", "-d", "--no-build" if args.no_build else "--build", "--wait", "--wait-timeout", "180"], check=True)


if __name__ == "__main__":
    main()
