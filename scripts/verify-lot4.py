"""Validate public quote issuance and admin access on the local Compose demo."""
from copy import deepcopy
import http.client
import io
import json
import runpy
import secrets
import subprocess
import uuid
from pathlib import Path

support = runpy.run_path(str(Path(__file__).with_name("verify-lot2.py")))
request = support["request"]
admin_url = support["ADMIN"]
public = "http://127.0.0.1:8080"
quotes = public + "/api/v1/quotes"


def offer(origin, destination, mode, reference=None):
    found, _ = request(public + "/api/v1/search", "POST", {
        "origin": origin, "destination": destination, "date": "2026-11-15", "dateFin": "2026-11-16", "modes": [mode],
    })
    return next(item for item in found["offers"] if not reference or item["offerId"] == reference)


def segment(index, entry, departure, arrival, **car):
    return {
        "segmentIndex": index, "origin": entry["origin"]["cityCode"], "destination": entry["destination"]["cityCode"],
        "plannedDeparture": departure, "plannedArrival": arrival,
        "allocations": [{"operator": entry["operator"], "offerId": entry["offerId"], "passengerCount": 2,
                         "participantAliases": ["p1", "p2"], **car}],
    }


def internal(path, token, body, expected=200):
    payload = json.dumps(body).encode()
    message = (f"POST {path} HTTP/1.1\r\nHost: tarification:8080\r\n"
               f"Authorization: Bearer {token}\r\nContent-Type: application/json\r\n"
               f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode() + payload
    command = "exec 3<>/dev/tcp/tarification/8080; cat >&3; cat <&3"
    output = subprocess.run(["docker", "compose", "--profile", "app", "exec", "-T", "api-gateway", "bash", "-ec", command],
                            input=message, capture_output=True, check=True, timeout=20).stdout

    class Socket:
        def makefile(self, *_):
            return io.BytesIO(output)

    with http.client.HTTPResponse(Socket()) as response:
        response.begin()
        assert response.status == expected, (path, response.status, expected)
        data = response.read()
        return json.loads(data) if data else None


def main():
    admin = support["admin_token"]()
    roles = {name: request(admin_url + "/roles/" + name, token=admin)[0] for name in ("CUSTOMER", "PRICING_ADMIN")}
    users = []
    try:
        tokens = []
        for assigned in ([roles["CUSTOMER"], roles["PRICING_ADMIN"]], [roles["CUSTOMER"]]):
            username = "pricing-smoke-" + secrets.token_hex(5)
            password = secrets.token_urlsafe(20)
            users.append(support["create_user"](admin, username, password, assigned))
            tokens.append(support["login"]("mobility-web", username, password))
        owner, other = tokens
        train = offer("CAS", "RAB", "TRAIN", "RAIL-CAS-RAB-0730")
        air = offer("RAB", "PAR", "AVION", "AIR-RBA-ORY-1200")
        car = offer("PAR", "VERSAILLES", "VOITURE")
        selection = {"travelerCount": 2, "participants": ["p1", "p2"], "promoCode": "DEMO", "segments": [
            segment(0, train, train["departureAt"], train["arrivalAt"]),
            segment(1, air, air["departureAt"], air["arrivalAt"]),
            segment(2, car, "2026-11-15T17:00:00+01:00", "2026-11-16T12:00:00+01:00",
                    startDate="2026-11-15", endDate="2026-11-16", driverAlias="p1"),
        ]}
        request(quotes, "POST", selection, expected=401)
        quote, _ = request(quotes, "POST", selection, token=owner, expected=201)
        assert quote["price"]["total"] == {"amountMinor": 409500, "currency": "MAD"}, quote["price"]["total"]
        assert len(quote["price"]["lines"]) == 3
        path = quotes + "/" + quote["id"]
        saved, _ = request(path, token=owner)
        assert saved == quote
        request(path, token=other, headers={"X-User-Id": users[0]}, expected=404)
        forged = deepcopy(selection)
        forged["segments"][0]["allocations"][0]["offerId"] = "invented"
        request(quotes, "POST", forged, token=owner, expected=409)
        forged["segments"][0]["allocations"][0]["unitPriceMinor"] = 1
        request(quotes, "POST", forged, token=owner, expected=400)
        malformed = deepcopy(selection)
        malformed["segments"][2]["allocations"][0]["participantAliases"] = ["p1", "p1"]
        request(quotes, "POST", malformed, token=owner, expected=400)
        impossible = deepcopy(selection)
        impossible["segments"][2]["plannedDeparture"] = "2026-11-15T15:20:00+01:00"
        request(quotes, "POST", impossible, token=owner, expected=400)
        request(public + "/api/v1/admin/regles", token=other, expected=403)
        rules, _ = request(public + "/api/v1/admin/regles", token=owner)
        assert len(rules) >= 2
        invalid_rule = {"type": "PERCENT_DISCOUNT", "condition": {"unsupported": True}, "rateBps": 100}
        request(public + "/api/v1/admin/regles/" + str(uuid.uuid4()), "PUT", invalid_rule, token=owner, expected=400)
        admin = support["admin_token"]()
        issue_token = support["service_token"](admin)
        claim_token = support["service_token"](admin, "reservation-service")
        price = quote["price"]
        preview = internal("/internal/v1/pricing/preview", issue_token, {"baskets": [
            {"itinerary": price["itinerary"], "promoCode": "DEMO", "categories": price["categories"]},
        ]})
        assert preview[0]["total"] == price["total"]
        assert all(line["lineId"] is None for line in preview[0]["lines"])
        claim_path = "/internal/v1/quotes/" + quote["id"] + "/claim"
        claim = {"actorUserId": users[0], "reservationId": str(uuid.uuid4())}
        internal(claim_path, issue_token, claim, expected=403)
        claimed = internal(claim_path, claim_token, claim)
        assert claimed["reservationId"] == claim["reservationId"] and claimed["status"] == "CLAIMED"
        assert internal(claim_path, claim_token, claim) == claimed
        internal(claim_path, claim_token, {**claim, "reservationId": str(uuid.uuid4())}, expected=409)
        print("Lot 4 OK: 409500 MAD minor, verified offers, preview, claim replay and authorization.")
    finally:
        admin = support["admin_token"]()
        for user in users:
            request(admin_url + "/users/" + user, "DELETE", token=admin, expected=204)


if __name__ == "__main__":
    main()
