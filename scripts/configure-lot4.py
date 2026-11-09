"""Apply the additive lot 4 client scopes to an existing local demo realm."""
import json
import runpy
from pathlib import Path

support = runpy.run_path(str(Path(__file__).with_name("verify-lot2.py")))
request = support["request"]
admin_url = support["ADMIN"]
admin = support["admin_token"]()
realm = json.loads(Path("infra/keycloak/mobility-realm.json").read_text(encoding="utf-8"))
for name in ("pricing:issue", "pricing:claim", "operators:write", "reservations:outcome"):
    scopes, _ = request(admin_url + "/client-scopes", token=admin)
    if not any(scope["name"] == name for scope in scopes):
        spec = next(scope for scope in realm["clientScopes"] if scope["name"] == name)
        request(admin_url + "/client-scopes", "POST", spec, token=admin, expected=201)

scopes, _ = request(admin_url + "/client-scopes", token=admin)
scope_ids = {scope["name"]: scope["id"] for scope in scopes}
for client_id, name in (("search-bff", "pricing:issue"), ("reservation-service", "pricing:claim"), ("reservation-service", "operators:write"), ("reservation-service", "offers:read"), ("pricing-service", "reservations:outcome")):
    clients, _ = request(admin_url + "/clients?clientId=" + client_id, token=admin)
    if not clients:
        spec = next(client for client in realm["clients"] if client["clientId"] == client_id)
        request(admin_url + "/clients", "POST", spec, token=admin, expected=201)
        clients, _ = request(admin_url + "/clients?clientId=" + client_id, token=admin)
    path = admin_url + "/clients/" + clients[0]["id"] + "/default-client-scopes"
    client_scopes, _ = request(path, token=admin)
    if not any(scope["name"] == name for scope in client_scopes):
        request(path + "/" + scope_ids[name], "PUT", token=admin, expected=204)
print("Pricing and operator scopes configured")
