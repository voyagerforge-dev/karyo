#!/usr/bin/env python3
"""Migrate an existing Karyo production realm to the current security contract."""

from __future__ import annotations

import argparse
import base64
import ipaddress
import json
import os
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from validate_credential import CredentialPolicyError, validate_credential
from validate_public_origin import PublicOriginError, ToolchainError, normalize_public_origin

REALM = "karyo"
WEB_CLIENT_ID = "karyo-web"
ADMIN_CLIENT_ID = "karyo-admin"
BACKEND_CLIENT_ID = "karyo-backend"
REDIRECT_PATHS = ("/", "/silent-check-sso.html", "/m/", "/m/silent-check-sso.html")
# Ceiling for the post-revocation token drain. The drain holds karyo-app, nginx and every
# ordinary Keycloak route down until already-issued access tokens expire, so its length is an
# outage the operator must consent to before anything irreversible happens. The realm's own
# accessTokenLifespan (900s) sits well inside this bound; a single client carrying a long
# `access.token.lifespan` is what pushes it past, and that is a misconfiguration worth stopping
# for rather than a wait worth sitting through with the stack dead.
DRAIN_CEILING_SECONDS = 3600
# The seeded default credential each legacy demo identity shipped with. A candidate must still
# authenticate with it before this migration will touch the account: an operator who adopted
# `admin` as their real administrator and rotated its password matches the profile fingerprint
# exactly, and deleting or disabling them on the strength of that match alone would lock them out.
LEGACY_USERS = {
    "admin": {
        "password": "admin",
        "email": "admin@karyo.local",
        "firstName": "System",
        "lastName": "Administrator",
        "attributes": {
            "client_id": ["0"],
            "principal_kind": ["ops"],
            "tenant_code": ["SYS"],
        },
        "roles": {"ADMIN"},
    },
    "manager": {
        "password": "manager",
        "email": "manager@acme.local",
        "firstName": "Alice",
        "lastName": "Manager",
        "attributes": {
            "client_id": ["1"],
            "principal_kind": ["ops"],
            "tenant_code": ["ACME"],
            "warehouse_id": ["WH-001"],
        },
        "roles": {"MANAGER", "integration-admin"},
    },
    "operator": {
        "password": "operator",
        "email": "operator@acme.local",
        "firstName": "Bob",
        "lastName": "Operator",
        "attributes": {
            "client_id": ["1"],
            "principal_kind": ["ops"],
            "tenant_code": ["ACME"],
            "warehouse_id": ["WH-001"],
        },
        "roles": {"OPERATOR"},
    },
    "viewer": {
        "password": "viewer",
        "email": "viewer@acme.local",
        "firstName": "Carol",
        "lastName": "Viewer",
        "attributes": {
            "client_id": ["1"],
            "principal_kind": ["ops"],
            "tenant_code": ["ACME"],
        },
        "roles": {"VIEWER"},
    },
    "tenant2-operator": {
        "password": "operator",
        "email": "operator@globex.local",
        "firstName": "Dave",
        "lastName": "Operator",
        "attributes": {
            "client_id": ["2"],
            "principal_kind": ["owner"],
            "tenant_code": ["GLOBEX"],
            "warehouse_id": ["WH-002"],
        },
        "roles": {"OPERATOR"},
    },
}


class MigrationError(RuntimeError):
    pass


class PartialMigrationError(MigrationError):
    pass


class MaintenanceAuthenticationRejected(MigrationError):
    pass


class RefuseRedirects(HTTPRedirectHandler):
    def redirect_request(
        self,
        req: Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> None:
        return None


_KEYCLOAK_OPENER = build_opener(ProxyHandler({}), RefuseRedirects)


def open_keycloak_request(request: Request, timeout: int = 20) -> Any:
    return _KEYCLOAK_OPENER.open(request, timeout=timeout)


class KeycloakAdminApi:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.write_attempted = False

    def request(
        self,
        method: str,
        path: str,
        body: Any | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> Any | None:
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(
            f"{self.base_url}{path}",
            data=payload,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        if method != "GET":
            self.write_attempted = True
        try:
            with open_keycloak_request(request, timeout=20) as response:
                status = response.status
                content = response.read()
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            exc.close()
            raise MigrationError(f"Keycloak {method} {path} returned {exc.code}: {detail}") from exc
        except URLError as exc:
            raise MigrationError(f"Keycloak request failed: {exc.reason}") from exc
        if status not in expected:
            raise MigrationError(f"Keycloak {method} {path} returned unexpected HTTP {status}")
        return json.loads(content) if content else None

    def get(self, path: str) -> Any:
        return self.request("GET", path)

    def post(self, path: str, body: Any, expected: tuple[int, ...] = (201, 204)) -> Any | None:
        return self.request("POST", path, body, expected)

    def put(self, path: str, body: Any) -> None:
        self.request("PUT", path, body, (204,))

    def delete(self, path: str, body: Any | None = None) -> None:
        self.request("DELETE", path, body, (204,))


def required_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value or "CHANGE_ME" in value:
        raise MigrationError(f"{name} must be supplied externally without CHANGE_ME placeholders")
    return value


def maintenance_url() -> str:
    port_value = os.environ.get("KARYO_KEYCLOAK_MAINTENANCE_PORT", "8181")
    try:
        port = int(port_value)
    except ValueError as exc:
        raise MigrationError("KARYO_KEYCLOAK_MAINTENANCE_PORT must be an integer") from exc
    if port < 1 or port > 65535:
        raise MigrationError("KARYO_KEYCLOAK_MAINTENANCE_PORT must be between 1 and 65535")
    value = os.environ.get(
        "KARYO_KEYCLOAK_MAINTENANCE_URL",
        f"http://127.0.0.1:{port}/auth",
    ).rstrip("/")
    parsed = urlsplit(value)
    try:
        parsed_port = parsed.port
    except ValueError as exc:
        raise MigrationError("KARYO_KEYCLOAK_MAINTENANCE_URL has an invalid port") from exc
    host = parsed.hostname
    loopback = host == "localhost"
    if host and not loopback:
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = False
    if (
        parsed.scheme != "http"
        or not loopback
        or parsed_port is None
        or parsed.path != "/auth"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise MigrationError(
            "KARYO_KEYCLOAK_MAINTENANCE_URL must be the loopback-only HTTP /auth endpoint "
            "with an explicit port"
        )
    return value


def admin_token(base_url: str, username: str, password: str) -> str:
    payload = urlencode(
        {
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": username,
            "password": password,
        }
    ).encode("utf-8")
    request = Request(
        f"{base_url}/realms/master/protocol/openid-connect/token",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with open_keycloak_request(request, timeout=20) as response:
            result = json.loads(response.read())
    except HTTPError as exc:
        exc.close()
        if exc.code in (400, 401):
            raise MaintenanceAuthenticationRejected(
                f"temporary maintenance administrator authentication failed: HTTP {exc.code}"
            ) from exc
        raise MigrationError(f"temporary maintenance administrator authentication failed: HTTP {exc.code}") from exc
    except URLError as exc:
        raise MigrationError(f"Keycloak authentication failed: {exc.reason}") from exc
    token = result.get("access_token")
    if not isinstance(token, str) or not token:
        raise MigrationError("Keycloak returned no maintenance access token")
    return token


def maintenance_credentials() -> tuple[str, str, str]:
    base_url = maintenance_url()
    username = required_env("KARYO_KEYCLOAK_MAINTENANCE_USERNAME")
    password = required_env("KARYO_KEYCLOAK_MAINTENANCE_PASSWORD")
    try:
        validate_credential(password, 16, username)
    except CredentialPolicyError as exc:
        raise MigrationError(f"privileged Keycloak credential {exc}") from exc
    return base_url, username, password


def maintenance_admin(
    base_url: str,
    username: str,
    password: str,
) -> tuple[KeycloakAdminApi, dict[str, Any]]:
    token = admin_token(base_url, username, password)
    api = KeycloakAdminApi(base_url, token)
    query = urlencode({"username": username, "exact": "true"})
    matches = api.get(f"/admin/realms/master/users?{query}")
    if len(matches) != 1:
        raise MigrationError("maintenance token username does not resolve to exactly one user")
    user = api.get(f"/admin/realms/master/users/{matches[0]['id']}")
    if user.get("username") != username:
        raise MigrationError("maintenance token username does not match its master-realm user")
    return api, user


def validate_maintenance_credentials() -> int:
    maintenance_credentials()
    print("Temporary maintenance administrator credentials satisfy policy")
    return 0


def verify_maintenance_admin() -> int:
    base_url, username, password = maintenance_credentials()
    maintenance_admin(base_url, username, password)
    print("Temporary maintenance administrator verified through the loopback-only endpoint")
    return 0


def retire_maintenance_admin() -> int:
    base_url, username, password = maintenance_credentials()
    api, user = maintenance_admin(base_url, username, password)
    try:
        api.delete(f"/admin/realms/master/users/{user['id']}")
        try:
            admin_token(base_url, username, password)
        except MaintenanceAuthenticationRejected:
            print("Temporary maintenance administrator retired and its credentials rejected")
            return 0
        raise MigrationError("retired maintenance administrator credentials still authenticate")
    except BaseException as exc:
        if api.write_attempted:
            detail = str(exc) or type(exc).__name__
            raise PartialMigrationError(detail) from exc
        raise


def client(api: KeycloakAdminApi, client_id: str) -> dict[str, Any] | None:
    matches = api.get(f"/admin/realms/{REALM}/clients?{urlencode({'clientId': client_id})}")
    if len(matches) > 1:
        raise MigrationError(f"multiple Keycloak clients use clientId {client_id}")
    if not matches:
        return None
    return api.get(f"/admin/realms/{REALM}/clients/{matches[0]['id']}")


def probe_client(api: KeycloakAdminApi) -> str:
    """Pick the realm client the seeded-credential probe authenticates through.

    `admin-cli` is Keycloak's own per-realm public direct-grant client. It is used rather than
    `karyo-backend` on purpose: this migration disables `karyo-backend`'s direct access grants,
    so probing through it would make a rerun after a partially applied migration impossible.
    """
    candidate = client(api, "admin-cli")
    if (
        candidate is None
        or not candidate.get("enabled", False)
        or not candidate.get("publicClient", False)
        or not candidate.get("directAccessGrantsEnabled", False)
    ):
        raise MigrationError(
            "realm client admin-cli is unavailable for the seeded-credential probe; the "
            "migration cannot confirm which identities still hold their seeded passwords and "
            "refuses to touch them"
        )
    return "admin-cli"


def seeded_credential_holds(base_url: str, probe: str, username: str, password: str) -> bool:
    """Authenticate one candidate with its seeded default credential.

    Returns True only on a successful grant. An authentication rejection returns False; anything
    else (transport failure, unexpected status, a token-less body) raises, because an
    inconclusive probe must refuse the migration rather than be read as a match. Neither the
    credential nor the token is logged or returned.
    """
    payload = urlencode(
        {
            "grant_type": "password",
            "client_id": probe,
            "username": username,
            "password": password,
            "scope": "openid",
        }
    ).encode("utf-8")
    request = Request(
        f"{base_url}/realms/{REALM}/protocol/openid-connect/token",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with open_keycloak_request(request, timeout=20) as response:
            result = json.loads(response.read())
    except HTTPError as exc:
        exc.close()
        if exc.code in (400, 401):
            return False
        raise MigrationError(
            f"seeded-credential probe for {username} failed inconclusively: HTTP {exc.code}"
        ) from exc
    except URLError as exc:
        raise MigrationError(
            f"seeded-credential probe for {username} failed inconclusively: {exc.reason}"
        ) from exc
    if not isinstance(result.get("access_token"), str) or not result["access_token"]:
        raise MigrationError(f"seeded-credential probe for {username} returned no access token")
    return True


def legacy_users(api: KeycloakAdminApi, base_url: str) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    probe = None
    for username, fingerprint in LEGACY_USERS.items():
        query = urlencode({"username": username, "exact": "true"})
        matches = api.get(f"/admin/realms/{REALM}/users?{query}")
        if not matches:
            continue
        if len(matches) != 1:
            raise MigrationError(f"multiple users match legacy username {username}")
        user = api.get(f"/admin/realms/{REALM}/users/{matches[0]['id']}")
        attributes = user.get("attributes") or {}
        role_mappings = api.get(f"/admin/realms/{REALM}/users/{user['id']}/role-mappings")
        roles = {role["name"] for role in role_mappings.get("realmMappings") or []}
        roles.discard(f"default-roles-{REALM}")
        client_roles = {
            mapping.get("client", client_id): sorted(
                role["name"] for role in mapping.get("mappings") or []
            )
            for client_id, mapping in (role_mappings.get("clientMappings") or {}).items()
            if mapping.get("mappings")
        }
        groups = api.get(f"/admin/realms/{REALM}/users/{user['id']}/groups")
        federated_identities = api.get(
            f"/admin/realms/{REALM}/users/{user['id']}/federated-identity"
        )
        credentials = api.get(f"/admin/realms/{REALM}/users/{user['id']}/credentials")
        credential_types = sorted(credential.get("type", "unknown") for credential in credentials)
        consents = api.get(f"/admin/realms/{REALM}/users/{user['id']}/consents")
        mismatches = [
            field
            for field in ("email", "firstName", "lastName")
            if user.get(field) != fingerprint[field]
        ]
        if user.get("username") != username:
            mismatches.append("username")
        if attributes != fingerprint["attributes"]:
            mismatches.append("attributes")
        if roles != fingerprint["roles"]:
            mismatches.append(
                f"realm roles expected {sorted(fingerprint['roles'])}, actual {sorted(roles)}"
            )
        if client_roles:
            mismatches.append(f"client roles {client_roles}")
        if groups:
            mismatches.append("groups")
        if federated_identities:
            mismatches.append("federated identities")
        if credential_types != ["password"]:
            mismatches.append(
                f"credentials expected one password, actual {credential_types}"
            )
        if consents:
            mismatches.append("client consents")
        if user.get("requiredActions"):
            mismatches.append("required actions")
        if user.get("emailVerified", False):
            mismatches.append("verified email")
        if user.get("totp", False):
            mismatches.append("configured OTP")
        if user.get("federationLink"):
            mismatches.append("federation link")
        if user.get("serviceAccountClientId"):
            mismatches.append("service account marker")
        if mismatches:
            raise MigrationError(
                f"user {username} no longer matches the complete legacy demo identity "
                f"({', '.join(mismatches)}); review it manually before rerunning"
            )
        if not user.get("enabled", True):
            raise MigrationError(
                f"user {username} matches the legacy demo profile but is disabled, so its seeded "
                "credential cannot be confirmed. Refusing without mutating the realm; either "
                f"re-enable {username} for the probe or resolve the account manually"
            )
        if probe is None:
            probe = probe_client(api)
        if not seeded_credential_holds(base_url, probe, username, fingerprint["password"]):
            raise MigrationError(
                f"user {username} matches the legacy demo profile but no longer authenticates "
                "with its seeded default credential, so it is an operational account whose "
                "password was rotated. Refusing the whole migration without mutating the realm; "
                "rename or retire that account yourself, then rerun"
            )
        candidates.append(user)
    return candidates


def access_token_lifespan(api: KeycloakAdminApi) -> tuple[int, str]:
    realm = api.get(f"/admin/realms/{REALM}")
    sourced = [
        ("realm accessTokenLifespan", realm.get("accessTokenLifespan")),
        (
            "realm accessTokenLifespanForImplicitFlow",
            realm.get("accessTokenLifespanForImplicitFlow"),
        ),
    ]
    sourced.extend(
        (
            f"client {client_rep.get('clientId')} attribute access.token.lifespan",
            (client_rep.get("attributes") or {}).get("access.token.lifespan"),
        )
        for client_rep in all_clients(api)
    )
    positive = []
    for source, value in sourced:
        if value is None or (isinstance(value, str) and not value.strip()):
            continue
        try:
            parsed = int(value)
        except (TypeError, ValueError) as exc:
            raise MigrationError(
                f"Keycloak returned an invalid access-token lifespan for {source}: {value!r}"
            ) from exc
        if parsed > 0:
            positive.append((parsed, source))
    if not positive:
        raise MigrationError("Keycloak returned no positive access-token lifespan")
    return max(positive, key=lambda item: item[0])


def revoke_sessions(api: KeycloakAdminApi, acted_on: list[dict[str, Any]]) -> None:
    api.post(f"/admin/realms/{REALM}/logout-all", None, (200, 204))
    for user in acted_on:
        api.post(f"/admin/realms/{REALM}/users/{user['id']}/logout", None, (200, 204))


def plan_access_token_drain(api: KeycloakAdminApi, ceiling: int) -> int:
    """Resolve, bound and announce the token drain BEFORE any irreversible action.

    The drain is an outage: sessions are revoked and the stack stays down until every
    already-issued access token has expired. Its length is decided by whichever realm or client
    lifespan is longest, so an operator can only consent to it if they are told how long it is
    while they can still walk away. Announcing it from inside the sleep - after sessions are
    revoked and legacy accounts are already deleted - tells them at the one moment they can no
    longer act on it, which is why this runs first and the sleep only reports what was agreed.
    """
    seconds, source = access_token_lifespan(api)
    if seconds > ceiling:
        raise MigrationError(
            f"access-token drain would hold the stack down for {seconds} seconds "
            f"({source}), beyond the {ceiling}-second ceiling. Nothing has been changed. "
            f"Either lower that lifespan in Keycloak and rerun, or rerun with "
            f"--max-drain-seconds {seconds} to accept an outage that long"
        )
    print(
        f"Planned access-token drain: {seconds} seconds (longest lifespan: {source}). "
        f"karyo-app, nginx, and every ordinary Keycloak route stay stopped for that long "
        f"after sessions are revoked.",
        flush=True,
    )
    return seconds


def drain_access_tokens(seconds: int) -> None:
    print(
        f"Security sessions revoked; keep karyo-app, nginx, and all ordinary Keycloak routes "
        f"stopped while issued access tokens drain for {seconds} seconds",
        flush=True,
    )
    time.sleep(seconds)


def all_clients(api: KeycloakAdminApi) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    first = 0
    while True:
        page = api.get(
            f"/admin/realms/{REALM}/clients?{urlencode({'first': first, 'max': 100, 'briefRepresentation': 'false'})}"
        )
        if not isinstance(page, list):
            raise MigrationError("Keycloak returned an invalid client page")
        if not page:
            return result
        result.extend(page)
        first += len(page)


def configure_web_client(api: KeycloakAdminApi, origin: str) -> None:
    web = client(api, WEB_CLIENT_ID)
    if web is None:
        raise MigrationError(f"Keycloak client {WEB_CLIENT_ID} is missing")
    web["redirectUris"] = [f"{origin}{path}" for path in REDIRECT_PATHS]
    web["webOrigins"] = [origin]
    api.put(f"/admin/realms/{REALM}/clients/{web['id']}", web)


def allowed_service_mapper(client_id: str, mapper: dict[str, Any]) -> bool:
    if client_id != BACKEND_CLIENT_ID:
        return False
    config = mapper.get("config") or {}
    claim = config.get("claim.name")
    return (
        mapper.get("protocol") == "openid-connect"
        and mapper.get("protocolMapper") == "oidc-usermodel-attribute-mapper"
        and claim in {"client_id", "principal_kind", "tenant_code", "warehouse_id"}
        and config.get("user.attribute") == claim
    )


def normalize_service_client(api: KeycloakAdminApi, configured: dict[str, Any]) -> None:
    client_id = configured["clientId"]
    client_uuid = configured["id"]
    mapper_path = f"/admin/realms/{REALM}/clients/{client_uuid}/protocol-mappers/models"
    for mapper in api.get(mapper_path):
        if not allowed_service_mapper(client_id, mapper):
            api.delete(f"{mapper_path}/{mapper['id']}")


def configure_backend_client(api: KeycloakAdminApi, secret: str) -> dict[str, Any]:
    backend = client(api, BACKEND_CLIENT_ID)
    if backend is None:
        raise MigrationError(f"Keycloak client {BACKEND_CLIENT_ID} is missing")
    backend["directAccessGrantsEnabled"] = False
    backend["standardFlowEnabled"] = False
    backend["serviceAccountsEnabled"] = True
    backend["fullScopeAllowed"] = True
    backend["secret"] = secret
    api.put(f"/admin/realms/{REALM}/clients/{backend['id']}", backend)
    configured = client(api, BACKEND_CLIENT_ID)
    if configured is None:
        raise MigrationError(f"Keycloak client {BACKEND_CLIENT_ID} disappeared during migration")
    normalize_service_client(api, configured)
    return configured


def configure_admin_client(api: KeycloakAdminApi, secret: str) -> dict[str, Any]:
    existing = client(api, ADMIN_CLIENT_ID)
    desired = {
        "clientId": ADMIN_CLIENT_ID,
        "name": "Karyo User Administration",
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "secret": secret,
        "standardFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True,
        "fullScopeAllowed": True,
    }
    if existing is None:
        api.post(f"/admin/realms/{REALM}/clients", desired, (201,))
    else:
        existing.update(desired)
        api.put(f"/admin/realms/{REALM}/clients/{existing['id']}", existing)
    configured = client(api, ADMIN_CLIENT_ID)
    if configured is None:
        raise MigrationError(f"Keycloak client {ADMIN_CLIENT_ID} was not created")
    normalize_service_client(api, configured)
    return configured


def role_key(role: dict[str, Any]) -> tuple[str, str]:
    return str(role.get("id") or "missing-id"), role["name"]


def role_closure(api: KeycloakAdminApi, roots: list[dict[str, Any]]) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    pending = list(roots)
    visited: set[str] = set()
    while pending:
        role = pending.pop()
        role_id = role.get("id")
        if not role_id or role_id in visited:
            continue
        visited.add(role_id)
        result.add(role_key(role))
        if role.get("composite", False):
            pending.extend(
                api.get(f"/admin/realms/{REALM}/roles-by-id/{role_id}/composites")
            )
    return result


def effective_roles(
    api: KeycloakAdminApi,
    user_id: str,
    clients: list[dict[str, Any]],
) -> set[tuple[str, str]]:
    result = {
        role_key(role)
        for role in api.get(
            f"/admin/realms/{REALM}/users/{user_id}/role-mappings/realm/composite"
        )
    }
    for client_rep in clients:
        result.update(
            role_key(role)
            for role in api.get(
                f"/admin/realms/{REALM}/users/{user_id}/role-mappings/clients/"
                f"{client_rep['id']}/composite"
            )
        )
    return result


def remove_service_user_authentication(api: KeycloakAdminApi, user_id: str) -> None:
    credential_path = f"/admin/realms/{REALM}/users/{user_id}/credentials"
    for credential in api.get(credential_path):
        api.delete(f"{credential_path}/{credential['id']}")
    identity_path = f"/admin/realms/{REALM}/users/{user_id}/federated-identity"
    for identity in api.get(identity_path):
        api.delete(f"{identity_path}/{quote(identity['identityProvider'], safe='')}")
    if api.get(credential_path) or api.get(identity_path):
        raise MigrationError("service account interactive authentication was not removed")


def set_service_roles(
    api: KeycloakAdminApi,
    service_client: dict[str, Any],
    desired_names: set[str],
) -> set[str]:
    management = client(api, "realm-management")
    if management is None:
        raise MigrationError("Keycloak realm-management client is missing")
    service_user = api.get(
        f"/admin/realms/{REALM}/clients/{service_client['id']}/service-account-user"
    )
    user_id = service_user["id"]
    remove_service_user_authentication(api, user_id)
    base_path = f"/admin/realms/{REALM}/users/{user_id}/role-mappings"
    direct = api.get(base_path)
    realm_roles = direct.get("realmMappings") or []
    if realm_roles:
        api.delete(f"{base_path}/realm", realm_roles)

    current_management: dict[str, dict[str, Any]] = {}
    for mapping in (direct.get("clientMappings") or {}).values():
        mapped_roles = mapping.get("mappings") or []
        mapped_client_id = mapping.get("id")
        if mapped_client_id == management["id"]:
            current_management = {role["name"]: role for role in mapped_roles}
            unwanted = [
                role for role in mapped_roles if role["name"] not in desired_names
            ]
            if unwanted:
                api.delete(f"{base_path}/clients/{mapped_client_id}", unwanted)
        elif mapped_roles:
            api.delete(f"{base_path}/clients/{mapped_client_id}", mapped_roles)

    for group in api.get(f"/admin/realms/{REALM}/users/{user_id}/groups"):
        api.delete(f"/admin/realms/{REALM}/users/{user_id}/groups/{group['id']}")

    desired_roles = [
        api.get(f"/admin/realms/{REALM}/clients/{management['id']}/roles/{quote(name)}")
        for name in sorted(desired_names)
    ]
    missing = [role for role in desired_roles if role["name"] not in current_management]
    if missing:
        api.post(f"{base_path}/clients/{management['id']}", missing, (204,))

    actual_direct = api.get(base_path)
    actual_realm = actual_direct.get("realmMappings") or []
    actual_clients = actual_direct.get("clientMappings") or {}
    actual_management = {
        role["name"]
        for mapping in actual_clients.values()
        if mapping.get("id") == management["id"]
        for role in mapping.get("mappings") or []
    }
    other_client_roles = {
        mapping.get("client", client_id): sorted(
            role["name"] for role in mapping.get("mappings") or []
        )
        for client_id, mapping in actual_clients.items()
        if mapping.get("id") != management["id"] and mapping.get("mappings")
    }
    groups = api.get(f"/admin/realms/{REALM}/users/{user_id}/groups")
    if actual_realm or actual_management != desired_names or other_client_roles or groups:
        raise MigrationError(
            f"service account {service_client['clientId']} direct authority was not normalized"
        )

    clients = all_clients(api)
    expected_effective = role_closure(api, desired_roles)
    actual_effective = effective_roles(api, user_id, clients)
    if actual_effective != expected_effective:
        raise MigrationError(
            f"service account {service_client['clientId']} has unexpected effective authority: "
            f"expected {sorted(expected_effective)}, actual {sorted(actual_effective)}"
        )
    return {name for _, name in expected_effective}


def token_role_authority(token: str) -> tuple[set[str], dict[str, set[str]]]:
    try:
        encoded = token.split(".")[1]
        padding = "=" * (-len(encoded) % 4)
        claims = json.loads(base64.urlsafe_b64decode(encoded + padding))
    except (IndexError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise MigrationError("Keycloak returned an invalid client-credentials access token") from exc
    realm_roles = set((claims.get("realm_access") or {}).get("roles") or [])
    client_roles = {
        resource: set(access.get("roles") or [])
        for resource, access in (claims.get("resource_access") or {}).items()
        if access.get("roles")
    }
    return realm_roles, client_roles


def client_credentials_grant(
    base_url: str,
    client_id: str,
    secret: str,
    expected_management_roles: set[str],
) -> None:
    payload = urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": secret,
        }
    ).encode("utf-8")
    request = Request(
        f"{base_url}/realms/{REALM}/protocol/openid-connect/token",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with open_keycloak_request(request, timeout=20) as response:
            result = json.loads(response.read())
    except HTTPError as exc:
        exc.close()
        raise MigrationError(f"{client_id} client-credentials grant failed: HTTP {exc.code}") from exc
    except URLError as exc:
        raise MigrationError(f"{client_id} client-credentials grant failed: {exc.reason}") from exc
    token = result.get("access_token")
    if not isinstance(token, str) or not token:
        raise MigrationError(f"{client_id} client-credentials grant returned no access token")
    realm_roles, client_roles = token_role_authority(token)
    expected_client_roles = {"realm-management": expected_management_roles}
    if realm_roles or client_roles != expected_client_roles:
        raise MigrationError(
            f"{client_id} token has unexpected authority: realm roles {sorted(realm_roles)}, "
            f"client roles {sorted((name, sorted(roles)) for name, roles in client_roles.items())}"
        )


def callbacks_match(web: dict[str, Any], expected_redirects: set[str], origin: str) -> bool:
    """Compare karyo-web's callback allowlists as sets, not sequences.

    Keycloak stores `redirectUris` and `webOrigins` in a Set and serializes them in hash order,
    so the array it returns is not the array that was written. An order-sensitive comparison here
    makes the migration mutate the realm and then always report failure for every public origin
    whose callbacks happen not to hash back into insertion order.
    """
    return (
        set(web.get("redirectUris") or []) == expected_redirects
        and set(web.get("webOrigins") or []) == {origin}
    )


def verify(api: KeycloakAdminApi, origin: str) -> None:
    remaining = []
    for username in LEGACY_USERS:
        query = urlencode({"username": username, "exact": "true"})
        if api.get(f"/admin/realms/{REALM}/users?{query}"):
            remaining.append(username)
    if remaining:
        raise MigrationError(
            "production realm still contains legacy demo users: "
            + ", ".join(sorted(remaining))
        )
    web = client(api, WEB_CLIENT_ID)
    expected_redirects = {f"{origin}{path}" for path in REDIRECT_PATHS}
    if web is None or not callbacks_match(web, expected_redirects, origin):
        raise MigrationError("karyo-web callbacks do not match KARYO_PUBLIC_ORIGIN")
    backend = client(api, BACKEND_CLIENT_ID)
    if backend is None or backend.get("directAccessGrantsEnabled") is not False:
        raise MigrationError("karyo-backend direct access grants are still enabled")


def service_secrets(maintenance_password: str) -> tuple[str, str]:
    admin_secret = required_env("KEYCLOAK_ADMIN_CLIENT_SECRET")
    oidc_secret = required_env("OIDC_SECRET")
    try:
        validate_credential(
            admin_secret,
            32,
            ADMIN_CLIENT_ID,
            require_env_literal=True,
        )
        validate_credential(
            oidc_secret,
            32,
            BACKEND_CLIENT_ID,
            require_env_literal=True,
        )
    except CredentialPolicyError as exc:
        raise MigrationError(f"privileged Keycloak credential {exc}") from exc
    if admin_secret == maintenance_password:
        raise MigrationError(
            "KEYCLOAK_ADMIN_CLIENT_SECRET must be distinct from maintenance credentials"
        )
    if oidc_secret in {admin_secret, maintenance_password}:
        raise MigrationError(
            "OIDC_SECRET must be distinct from administration and maintenance credentials"
        )
    return admin_secret, oidc_secret


def reconcile_service_clients(max_drain_seconds: int = DRAIN_CEILING_SECONDS) -> int:
    base_url, maintenance_username, maintenance_password = maintenance_credentials()
    admin_secret, oidc_secret = service_secrets(maintenance_password)
    api = KeycloakAdminApi(
        base_url,
        admin_token(base_url, maintenance_username, maintenance_password),
    )
    if client(api, BACKEND_CLIENT_ID) is None:
        raise MigrationError(f"Keycloak client {BACKEND_CLIENT_ID} is missing")
    if client(api, "realm-management") is None:
        raise MigrationError("Keycloak realm-management client is missing")
    drain_seconds = plan_access_token_drain(api, max_drain_seconds)
    try:
        admin = configure_admin_client(api, admin_secret)
        backend = configure_backend_client(api, oidc_secret)
        admin_token_roles = set_service_roles(
            api, admin, {"manage-users", "view-realm"}
        )
        backend_token_roles = set_service_roles(
            api, backend, {"view-events", "view-users"}
        )
        client_credentials_grant(
            base_url,
            BACKEND_CLIENT_ID,
            oidc_secret,
            backend_token_roles,
        )
        client_credentials_grant(
            base_url,
            ADMIN_CLIENT_ID,
            admin_secret,
            admin_token_roles,
        )
        revoke_sessions(api, [])
        drain_access_tokens(drain_seconds)
        api.token = admin_token(base_url, maintenance_username, maintenance_password)
        client_credentials_grant(
            base_url,
            BACKEND_CLIENT_ID,
            oidc_secret,
            backend_token_roles,
        )
        client_credentials_grant(
            base_url,
            ADMIN_CLIENT_ID,
            admin_secret,
            admin_token_roles,
        )
        print(
            "Karyo service clients reconciled; sessions revoked, access tokens drained, "
            "and least-privilege client-credentials grants verified"
        )
        return 0
    except BaseException as exc:
        if api.write_attempted:
            detail = str(exc) or type(exc).__name__
            raise PartialMigrationError(detail) from exc
        raise


def migrate() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--apply", action="store_true", help="apply the idempotent realm migration")
    mode.add_argument(
        "--validate-maintenance-credentials",
        action="store_true",
        help="validate temporary administrator credentials without contacting Keycloak",
    )
    mode.add_argument(
        "--verify-maintenance-admin",
        action="store_true",
        help="verify the temporary administrator through the loopback-only endpoint",
    )
    mode.add_argument(
        "--retire-maintenance-admin",
        action="store_true",
        help="delete the authenticated temporary administrator and verify rejection",
    )
    mode.add_argument(
        "--reconcile-service-clients",
        action="store_true",
        help="restore current least-privilege service clients without changing human users",
    )
    parser.add_argument(
        "--verified-backup",
        action="store_true",
        help="confirm the pre-migration Keycloak backup was restored and verified",
    )
    parser.add_argument(
        "--max-drain-seconds",
        type=int,
        default=DRAIN_CEILING_SECONDS,
        help=(
            "accept an access-token drain up to this many seconds; the migration refuses "
            f"before changing anything when the realm needs longer (default {DRAIN_CEILING_SECONDS})"
        ),
    )
    parser.add_argument(
        "--isolated-maintenance",
        action="store_true",
        help="confirm every ordinary application and reverse-proxy route to Keycloak is stopped",
    )
    args = parser.parse_args()
    if args.validate_maintenance_credentials:
        return validate_maintenance_credentials()
    if args.verify_maintenance_admin:
        return verify_maintenance_admin()
    if args.retire_maintenance_admin:
        return retire_maintenance_admin()
    if not args.isolated_maintenance:
        raise MigrationError(
            "--isolated-maintenance is required after stopping karyo-app, nginx, and every "
            "other ordinary Keycloak route; complete DEPLOY.md maintenance isolation first"
        )
    if args.reconcile_service_clients:
        return reconcile_service_clients(args.max_drain_seconds)
    if not args.verified_backup:
        raise MigrationError(
            "--verified-backup is required before irreversible legacy-user deletion; "
            "complete DEPLOY.md upgrade step 1 first"
        )

    try:
        origin = normalize_public_origin(required_env("KARYO_PUBLIC_ORIGIN"))
    except ToolchainError as exc:
        # Caught before PublicOriginError, which it subclasses: an absent interpreter is not a
        # malformed origin, and saying so would send the operator to fix a correct env file.
        raise MigrationError(f"cannot validate KARYO_PUBLIC_ORIGIN: {exc}") from exc
    except PublicOriginError as exc:
        raise MigrationError(f"invalid KARYO_PUBLIC_ORIGIN: {exc}") from exc
    base_url, maintenance_username, maintenance_password = maintenance_credentials()
    admin_secret, oidc_secret = service_secrets(maintenance_password)
    token = admin_token(base_url, maintenance_username, maintenance_password)
    api = KeycloakAdminApi(base_url, token)
    if client(api, WEB_CLIENT_ID) is None:
        raise MigrationError(f"Keycloak client {WEB_CLIENT_ID} is missing")

    candidates = legacy_users(api, base_url)
    drain_seconds = plan_access_token_drain(api, args.max_drain_seconds)

    try:
        configure_web_client(api, origin)
        admin = configure_admin_client(api, admin_secret)
        backend = configure_backend_client(api, oidc_secret)
        admin_token_roles = set_service_roles(
            api, admin, {"manage-users", "view-realm"}
        )
        backend_token_roles = set_service_roles(
            api, backend, {"view-events", "view-users"}
        )

        # The permanent identities are proven to work BEFORE anything is retired. Retiring first and
        # verifying afterwards can leave a realm with the demo accounts gone and no working service
        # account to administer it with.
        client_credentials_grant(
            base_url,
            BACKEND_CLIENT_ID,
            oidc_secret,
            backend_token_roles,
        )
        client_credentials_grant(
            base_url,
            ADMIN_CLIENT_ID,
            admin_secret,
            admin_token_roles,
        )

        revoke_sessions(api, candidates)
        for user in candidates:
            api.delete(f"/admin/realms/{REALM}/users/{user['id']}")
        drain_access_tokens(drain_seconds)
        api.token = admin_token(base_url, maintenance_username, maintenance_password)
        verify(api, origin)
        client_credentials_grant(
            base_url,
            BACKEND_CLIENT_ID,
            oidc_secret,
            backend_token_roles,
        )
        client_credentials_grant(
            base_url,
            ADMIN_CLIENT_ID,
            admin_secret,
            admin_token_roles,
        )
        retired_names = sorted(user["username"] for user in candidates)
        detail = f" ({', '.join(retired_names)})" if retired_names else ""
        print(
            f"Karyo realm migration complete: deleted {len(candidates)} legacy demo users{detail}; "
            "sessions revoked and access tokens drained; callbacks, service roles, and "
            "client-credentials grants verified"
        )
        return 0
    except BaseException as exc:
        if api.write_attempted:
            detail = str(exc) or type(exc).__name__
            raise PartialMigrationError(detail) from exc
        raise


def main() -> int:
    try:
        return migrate()
    except PartialMigrationError as exc:
        print(
            "REALM MIGRATION MAY BE PARTIALLY APPLIED after a Keycloak write was attempted. "
            "Its outcome is not safe to infer. Stop deployment and follow DEPLOY.md "
            "'Roll Back a Failed Realm Migration' before "
            f"retrying. Last error: {exc}",
            file=sys.stderr,
        )
        return 1
    except MigrationError as exc:
        print(f"realm migration failed before any Keycloak write was attempted: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
