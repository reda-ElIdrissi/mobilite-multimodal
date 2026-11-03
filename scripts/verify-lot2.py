"""Local Compose smoke test. Run against the disposable demo stack."""
import base64
import hashlib
import http.client
from http.cookiejar import CookieJar, DefaultCookiePolicy
import io
import json
import os
import secrets
import subprocess
import time
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    Request,
    build_opener,
    urlopen,
)

KEYCLOAK = "http://localhost:8088"
GATEWAY = "http://localhost:8080"
REALM = KEYCLOAK + "/realms/mobility"
ADMIN = KEYCLOAK + "/admin/realms/mobility"
REDIRECT = "http://localhost:3000/callback"
VEHICLES = "/api/v1/vehicules"


def request(url, method="GET", payload=None, token=None, expected=200, headers=None, form=False):
    headers = dict(headers or {})
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(payload) if form else json.dumps(payload)).encode()
    try:
        response = urlopen(Request(url, data, headers, method=method), timeout=12)
    except HTTPError as error:
        response = error
    with response:
        assert response.code == expected, (method, url, response.code, expected, response.headers.get("WWW-Authenticate"))
        body = response.read()
        return (json.loads(body) if body and response.headers.get_content_type() == "application/json" else None), response.headers


class Callback(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith(REDIRECT + "?"):
            return None
        assert urlsplit(newurl).netloc == urlsplit(KEYCLOAK).netloc
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class LoginForm(HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        if tag == "form" and dict(attrs).get("id") == "kc-form-login":
            self.action = dict(attrs)["action"]


def login(client, username, password):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(18)
    query = urlencode({
        "client_id": client, "redirect_uri": REDIRECT, "scope": "openid", "response_type": "code",
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
    })
    # Browsers treat localhost as trustworthy for Secure cookies. urllib needs this
    # explicit local-only policy while testing the HTTP development endpoint.
    cookies = CookieJar(policy=DefaultCookiePolicy(secure_protocols=("http", "https")))
    browser = build_opener(HTTPCookieProcessor(cookies), Callback())
    with browser.open(REALM + "/protocol/openid-connect/auth?" + query, timeout=12) as response:
        parser = LoginForm()
        parser.feed(response.read().decode())
    assert parser.action, "Keycloak login form not found"
    destination = urljoin(REALM, parser.action)
    assert urlsplit(destination).netloc == urlsplit(KEYCLOAK).netloc
    try:
        browser.open(Request(destination, urlencode({"username": username, "password": password}).encode()), timeout=12)
    except HTTPError as error:
        with error:
            location = error.headers["Location"]
            assert error.code in (302, 303) and location and location.startswith(REDIRECT + "?"), error.code
            callback = parse_qs(urlsplit(location).query)
            assert callback["state"] == [state]
            return request(REALM + "/protocol/openid-connect/token", "POST", {
                "grant_type": "authorization_code", "client_id": client, "redirect_uri": REDIRECT,
                "code": callback["code"][0], "code_verifier": verifier,
            }, form=True)[0]["access_token"]
    raise AssertionError("Keycloak did not redirect to the PKCE callback")


def identifier(headers):
    return headers["Location"].rstrip("/").split("/")[-1]


def create_user(admin, username, password, roles):
    _, headers = request(ADMIN + "/users", "POST", {
        "username": username, "enabled": True, "email": username + "@example.invalid",
        "emailVerified": True, "firstName": "Smoke", "lastName": "Test",
        "credentials": [{"type": "password", "value": password, "temporary": False}],
    }, token=admin, expected=201)
    user = identifier(headers)
    request(ADMIN + f"/users/{user}/role-mappings/realm", "POST", roles, token=admin, expected=204)
    return user


def service_secret(admin, client_id="search-bff"):
    clients, _ = request(ADMIN + "/clients?clientId=" + client_id, token=admin)
    secret, _ = request(ADMIN + f"/clients/{clients[0]['id']}/client-secret", token=admin)
    return secret["value"]


def service_token(admin, client_id="search-bff"):
    data, _ = request(REALM + "/protocol/openid-connect/token", "POST", {
        "grant_type": "client_credentials", "client_id": client_id, "client_secret": service_secret(admin, client_id),
    }, form=True)
    return data["access_token"]


def internal_search(token, query, expected=200):
    body = json.dumps(query).encode()
    raw = (
        f"POST /internal/v1/offers/search HTTP/1.1\r\nHost: autopartage:8080\r\n"
        f"Authorization: Bearer {token}\r\nContent-Type: application/json\r\n"
        f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n"
    ).encode() + body
    script = "exec 3<>/dev/tcp/autopartage/8080; cat >&3; cat <&3"
    output = subprocess.run(
        ["docker", "compose", "--profile", "app", "exec", "-T", "api-gateway", "bash", "-ec", script],
        input=raw, capture_output=True, check=True, timeout=20,
    ).stdout

    class Socket:
        def makefile(self, *_):
            return io.BytesIO(output)

    with http.client.HTTPResponse(Socket()) as response:
        response.begin()
        assert response.status == expected, (response.status, expected)
        data = response.read()
        return json.loads(data) if data else None


def await_services():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            request(REALM + "/.well-known/openid-configuration")
            request(GATEWAY + VEHICLES + "/mine", expected=401)
            return
        except (AssertionError, URLError, OSError):
            time.sleep(2)
    raise RuntimeError("Gateway or Keycloak did not become ready")


def admin_token():
    return request(KEYCLOAK + "/realms/master/protocol/openid-connect/token", "POST", {
        "grant_type": "password", "client_id": "admin-cli", "username": "mobility-admin",
        "password": os.environ["MOBILITY_ADMIN_PASSWORD"],
    }, form=True)[0]["access_token"]


def main():
    await_services()
    admin = admin_token()
    roles = {role: request(ADMIN + "/roles/" + role, token=admin)[0] for role in ("OWNER", "CUSTOMER")}
    nonce = secrets.token_hex(6)
    client = "smoke-audience-" + nonce
    users = []
    client_id = None
    vehicle_id = None
    owner_token = None
    try:
        _, headers = request(ADMIN + "/clients", "POST", {
            "clientId": client, "protocol": "openid-connect", "publicClient": True,
            "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
            "redirectUris": [REDIRECT], "attributes": {"pkce.code.challenge.method": "S256"},
            "defaultClientScopes": ["locations:read"],
        }, token=admin, expected=201)
        client_id = identifier(headers)
        passwords = [secrets.token_urlsafe(18) for _ in range(3)]
        usernames = [f"smoke-{nonce}-{index}" for index in range(3)]
        for index, (username, password) in enumerate(zip(usernames, passwords)):
            assigned = [roles["CUSTOMER"]] if index == 2 else list(roles.values())
            users.append(create_user(admin, username, password, assigned))
        owner_token, other_token, customer_token = [
            login("mobility-web", username, password) for username, password in zip(usernames, passwords)
        ]
        wrong_audience = login(client, usernames[0], passwords[0])
        head, claims, signature = owner_token.split(".")
        bogus = ".".join((head, claims, ("A" if signature[0] != "A" else "B") + signature[1:]))
        spoof = {"X-User-Id": users[0], "X-Owner-Id": users[0]}
        preflight = {"Origin": "http://localhost:3000", "Access-Control-Request-Method": "PATCH",
                     "Access-Control-Request-Headers": "authorization,content-type"}
        _, cors = request(GATEWAY + VEHICLES + "/1", "OPTIONS", headers=preflight)
        assert cors["Access-Control-Allow-Origin"] == "http://localhost:3000"
        request(GATEWAY + VEHICLES + "/1", "OPTIONS",
                headers={**preflight, "Origin": "http://untrusted.example.invalid"}, expected=403)
        request(GATEWAY + VEHICLES + "/mine", expected=401, headers=spoof)
        request(GATEWAY + VEHICLES + "/mine", token=bogus, expected=401)
        request(GATEWAY + VEHICLES + "/mine", token=wrong_audience, expected=401)
        request(GATEWAY + VEHICLES + "/mine", token=customer_token, expected=403)

        vehicle, _ = request(GATEWAY + VEHICLES, "POST", {
            "marque": "Test", "modele": "Smoke", "immatriculation": "SMOKE-" + nonce,
            "couleur": "Bleu", "ville": "Paris", "categorie": "Citadine",
            "dailyRateMinor": 24000, "passengerCapacity": 3,
        }, token=owner_token, expected=201)
        vehicle_id = vehicle["id"]
        url = GATEWAY + VEHICLES + f"/{vehicle_id}"
        assert vehicle["ownerId"] == users[0] and not vehicle["isAvailable"]
        request(url, "PATCH", {"couleur": "Vert"}, token=other_token, expected=403, headers=spoof)
        request(url + "/enable", "PATCH", token=owner_token, expected=409)
        request(url + "/destinations", "PUT", {"permissions": [{
            "destination": "Versailles", "debut": "2026-11-15", "fin": "2026-11-16",
        }]}, token=owner_token)
        enabled, _ = request(url + "/enable", "PATCH", token=owner_token)
        assert enabled["isAvailable"]
        edited, _ = request(url, "PATCH", {"dailyRateMinor": 25000}, token=owner_token)
        assert edited["priceVersion"] == vehicle["priceVersion"] + 1
        mine, _ = request(GATEWAY + VEHICLES + "/mine", token=owner_token)
        assert [item["id"] for item in mine["content"]] == [vehicle_id]
        history, _ = request(GATEWAY + "/api/v1/locations?role=owner", token=owner_token)
        assert history["content"] == []

        token = service_token(admin_token())
        query = {"origin": "PAR", "destination": "VERSAILLES", "date": "2026-11-15", "dateFin": "2026-11-16"}
        request(GATEWAY + "/internal/v1/offers/search", "POST", query, token=token, expected=403)
        internal_search(wrong_audience, query, expected=401)
        internal_search(bogus, query, expected=401)
        internal_search(owner_token, query, expected=403)
        offers = internal_search(token, query)
        assert any(offer["unitPriceMinor"] == 25000 and offer["capacity"] == 3 for offer in offers)
        assert all("immatriculation" not in offer and "ownerId" not in offer for offer in offers)
        assert all(offer["capacity"] >= 4 for offer in internal_search(token, {**query, "capacity": 4}))
        assert internal_search(token, {**query, "dateFin": "2026-11-17"}) == []
        print("Lot 2 OK: Keycloak PKCE, JWT signature and audience, ownership, permissions, offers and service scope.")
    finally:
        if vehicle_id and owner_token:
            request(GATEWAY + VEHICLES + f"/{vehicle_id}/disable", "PATCH", token=owner_token)
        admin = admin_token()
        for user in users:
            request(ADMIN + "/users/" + user, "DELETE", token=admin, expected=204)
        if client_id:
            request(ADMIN + "/clients/" + client_id, "DELETE", token=admin, expected=204)


if __name__ == "__main__":
    main()
