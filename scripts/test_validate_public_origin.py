#!/usr/bin/env python3

import io
import os
import subprocess
import sys
import tempfile
import unittest
import yaml
from pathlib import Path
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from unittest.mock import patch
from urllib.error import URLError
from urllib.parse import parse_qs, urlsplit

import migrate_keycloak_realm as migration
from validate_public_origin import PublicOriginError, normalize_public_origin, validate_deployment_urls
import validate_public_origin as validator


class PublicOriginValidationTest(unittest.TestCase):
    def test_accepts_canonical_dns_idn_and_ipv6_origins(self) -> None:
        valid = (
            "https://warehouse.example.com",
            "https://xn--bcher-kva.example",
            "http://127.0.0.1:8088",
            "https://[2001:db8::1]:8443",
        )
        for origin in valid:
            with self.subTest(origin=origin):
                self.assertEqual(normalize_public_origin(origin), origin)

    def test_rejects_noncanonical_or_unsafe_origins(self) -> None:
        invalid = (
            "https://user:password@warehouse.example.com",
            "https://warehouse.example.com/",
            "https://warehouse.example.com:443",
            "https://Warehouse.example.com",
            "https://bücher.example",
            "https://[2001:0db8::1]",
            "https://127.1",
            "https://warehouse.example.com/path",
            "https://warehouse.example.com?query=yes",
            "https://*.example.com",
        )
        for origin in invalid:
            with self.subTest(origin=origin):
                with self.assertRaises(PublicOriginError):
                    normalize_public_origin(origin)

    def test_requires_every_public_url_to_share_one_origin(self) -> None:
        self.assertEqual(
            validate_deployment_urls(
                "https://warehouse.example.com:8443",
                "warehouse.example.com",
                "https://warehouse.example.com:8443/auth",
                "https://warehouse.example.com:8443/auth",
            ),
            "https://warehouse.example.com:8443",
        )
        validate_deployment_urls(
            "https://warehouse.example.com",
            "warehouse.example.com",
            "https://warehouse.example.com/auth",
            "/auth",
        )
        invalid = (
            ("other.example.com", "https://warehouse.example.com/auth", "/auth"),
            ("warehouse.example.com", "https://other.example.com/auth", "/auth"),
            (
                "warehouse.example.com",
                "https://warehouse.example.com/auth",
                "https://warehouse.example.com:8443/auth",
            ),
        )
        for domain, hostname, keycloak_url in invalid:
            with self.subTest(domain=domain, hostname=hostname, keycloak_url=keycloak_url):
                with self.assertRaises(PublicOriginError):
                    validate_deployment_urls(
                        "https://warehouse.example.com",
                        domain,
                        hostname,
                        keycloak_url,
                    )


class ProductionComposeExposureTest(unittest.TestCase):
    """The compose files are the deployment contract podman/docker consumes verbatim."""

    def compose_services(self, name: str) -> dict:
        path = Path(__file__).resolve().parent.parent / "infrastructure" / "docker" / name
        return yaml.safe_load(path.read_text())["services"]

    def test_production_deployment_publishes_no_keycloak_port(self) -> None:
        services = self.compose_services("docker-compose.prod.yml")
        self.assertEqual(services["keycloak"].get("ports", []), [])
        publishing = sorted(
            name for name, definition in services.items() if definition.get("ports")
        )
        self.assertEqual(publishing, ["nginx"])

    def test_maintenance_override_publishes_keycloak_on_loopback_only(self) -> None:
        services = self.compose_services("docker-compose.maintenance.yml")
        self.assertEqual(sorted(services), ["keycloak"])
        self.assertEqual(
            services["keycloak"]["ports"],
            ["127.0.0.1:${KARYO_KEYCLOAK_MAINTENANCE_PORT:-8181}:8080"],
        )


class KeycloakMigrationFailureClassificationTest(unittest.TestCase):
    def test_lost_write_response_requires_partial_migration_recovery(self) -> None:
        api = migration.KeycloakAdminApi("https://identity.example.test", "token")
        with patch.object(
            migration,
            "open_keycloak_request",
            side_effect=URLError("response lost"),
        ):
            with self.assertRaises(migration.MigrationError):
                api.put("/admin/realms/karyo/clients/web", {"enabled": True})
        self.assertTrue(api.write_attempted)

    def test_read_failure_remains_a_mutation_free_refusal(self) -> None:
        api = migration.KeycloakAdminApi("https://identity.example.test", "token")
        with patch.object(
            migration,
            "open_keycloak_request",
            side_effect=URLError("unreachable"),
        ):
            with self.assertRaises(migration.MigrationError):
                api.get("/admin/realms/karyo/clients")
        self.assertFalse(api.write_attempted)

    def test_admin_api_refuses_bearer_redirects(self) -> None:
        received_authorization: list[str | None] = []

        class TargetHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                received_authorization.append(self.headers.get("Authorization"))
                self.send_response(204)
                self.end_headers()

            def log_message(self, _format: str, *_args: object) -> None:
                pass

        target = ThreadingHTTPServer(("127.0.0.1", 0), TargetHandler)
        target_thread = Thread(target=target.serve_forever, daemon=True)
        target_thread.start()

        class RedirectHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                self.send_response(302)
                self.send_header(
                    "Location",
                    f"http://127.0.0.1:{target.server_port}/captured",
                )
                self.end_headers()

            def log_message(self, _format: str, *_args: object) -> None:
                pass

        redirect = ThreadingHTTPServer(("127.0.0.1", 0), RedirectHandler)
        redirect_thread = Thread(target=redirect.serve_forever, daemon=True)
        redirect_thread.start()
        try:
            api = migration.KeycloakAdminApi(
                f"http://127.0.0.1:{redirect.server_port}",
                "master-realm-token",
            )
            with self.assertRaisesRegex(migration.MigrationError, "returned 302"):
                api.get("/admin/realms/karyo/clients")
            self.assertEqual(received_authorization, [])
        finally:
            redirect.shutdown()
            target.shutdown()
            redirect.server_close()
            target.server_close()
            redirect_thread.join()
            target_thread.join()

    def test_maintenance_requests_bypass_environment_proxies(self) -> None:
        target_requests: list[str] = []
        proxy_requests: list[str] = []

        class TargetHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                target_requests.append(self.path)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"access_token":"direct-token"}')

            def log_message(self, _format: str, *_args: object) -> None:
                pass

        class ProxyHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                proxy_requests.append(self.path)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"access_token":"proxy-token"}')

            def log_message(self, _format: str, *_args: object) -> None:
                pass

        target = ThreadingHTTPServer(("127.0.0.2", 0), TargetHandler)
        proxy = ThreadingHTTPServer(("127.0.0.1", 0), ProxyHandler)
        target_thread = Thread(target=target.serve_forever, daemon=True)
        proxy_thread = Thread(target=proxy.serve_forever, daemon=True)
        target_thread.start()
        proxy_thread.start()
        try:
            proxy_url = f"http://127.0.0.1:{proxy.server_port}"
            environment = dict(os.environ)
            environment.update(
                {
                    "http_proxy": proxy_url,
                    "HTTP_PROXY": proxy_url,
                    "all_proxy": proxy_url,
                    "ALL_PROXY": proxy_url,
                    "no_proxy": "",
                    "NO_PROXY": "",
                }
            )
            result = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    (
                        "from migrate_keycloak_realm import admin_token; "
                        f"print(admin_token('http://127.0.0.2:{target.server_port}/auth', "
                        "'maintenance-user', 'maintenance-password'))"
                    ),
                ],
                cwd=Path(__file__).resolve().parent,
                env=environment,
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.stdout.strip(), "direct-token")
            self.assertEqual(len(target_requests), 1)
            self.assertEqual(proxy_requests, [])
        finally:
            proxy.shutdown()
            target.shutdown()
            proxy.server_close()
            target.server_close()
            proxy_thread.join()
            target_thread.join()

    def test_maintenance_channel_rejects_public_and_credentialed_urls(self) -> None:
        invalid = (
            "https://identity.example.test/auth",
            "http://identity.example.test:8181/auth",
            "http://user:password@127.0.0.1:8181/auth",
            "http://127.0.0.1:8181/",
        )
        for value in invalid:
            with self.subTest(value=value), patch.dict(
                os.environ,
                {"KARYO_KEYCLOAK_MAINTENANCE_URL": value},
                clear=True,
            ):
                with self.assertRaisesRegex(migration.MigrationError, "loopback-only"):
                    migration.maintenance_url()

    def test_maintenance_credentials_are_validated_without_contacting_keycloak(self) -> None:
        environment = {
            "KARYO_KEYCLOAK_MAINTENANCE_URL": "http://127.0.0.1:8181/auth",
            "KARYO_KEYCLOAK_MAINTENANCE_USERNAME": "temporary-maintenance-admin",
            "KARYO_KEYCLOAK_MAINTENANCE_PASSWORD": "temporary-maintenance-admin",
        }
        stderr = io.StringIO()
        with patch.dict(os.environ, environment, clear=True), patch.object(
            sys,
            "argv",
            ["migrate_keycloak_realm.py", "--validate-maintenance-credentials"],
        ), patch.object(
            migration,
            "open_keycloak_request",
            side_effect=AssertionError("validation must not contact Keycloak"),
        ), redirect_stderr(stderr):
            self.assertEqual(migration.main(), 1)
        self.assertIn("must not match or be derived from its username", stderr.getvalue())

    def test_apply_requires_isolated_maintenance_before_authentication(self) -> None:
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), patch.object(
            sys,
            "argv",
            ["migrate_keycloak_realm.py", "--apply", "--verified-backup"],
        ), patch.object(
            migration,
            "admin_token",
            side_effect=AssertionError("authentication must not run"),
        ), redirect_stderr(stderr):
            self.assertEqual(migration.main(), 1)
        self.assertIn("--isolated-maintenance is required", stderr.getvalue())
        self.assertIn("before any Keycloak write", stderr.getvalue())

    def test_retirement_deletes_the_authenticated_subject_and_rejects_reauthentication(self) -> None:
        deleted: list[str] = []

        class FakeApi:
            write_attempted = False

            def delete(self, path: str) -> None:
                self.write_attempted = True
                deleted.append(path)

        api = FakeApi()
        user = {"id": "temporary-user-id", "username": "temporary-maintenance-admin"}
        with patch.object(
            migration,
            "maintenance_credentials",
            return_value=(
                "http://127.0.0.1:8181/auth",
                "temporary-maintenance-admin",
                "test-only-maintenance-password-4Nv8xQ",
            ),
        ), patch.object(
            migration,
            "maintenance_admin",
            return_value=(api, user),
        ), patch.object(
            migration,
            "admin_token",
            side_effect=migration.MaintenanceAuthenticationRejected("rejected"),
        ):
            self.assertEqual(migration.retire_maintenance_admin(), 0)
        self.assertEqual(deleted, ["/admin/realms/master/users/temporary-user-id"])

    def test_migration_rejects_unserializable_service_secret_before_authentication(self) -> None:
        environment = {
            "KARYO_PUBLIC_ORIGIN": "https://warehouse.example.test",
            "KARYO_KEYCLOAK_MAINTENANCE_URL": "http://127.0.0.1:8181/auth",
            "KARYO_KEYCLOAK_MAINTENANCE_USERNAME": "temporary-maintenance-admin",
            "KARYO_KEYCLOAK_MAINTENANCE_PASSWORD": "test-only-maintenance-password-4Nv8xQ",
            "KEYCLOAK_ADMIN_CLIENT_SECRET": "test-only-admin-client-secret-8Kp4zW",
            "OIDC_SECRET": "test-only-rotated$backend-secret-3Qm8wL",
        }
        stderr = io.StringIO()
        with patch.dict(os.environ, environment, clear=True), patch.object(
            sys,
            "argv",
            [
                "migrate_keycloak_realm.py",
                "--apply",
                "--verified-backup",
                "--isolated-maintenance",
            ],
        ), patch.object(
            migration,
            "admin_token",
            side_effect=AssertionError("authentication must not run"),
        ), redirect_stderr(stderr):
            self.assertEqual(migration.main(), 1)
        self.assertIn("unquoted environment literal", stderr.getvalue())
        self.assertIn("before any Keycloak write", stderr.getvalue())

    def test_migration_rejects_embedded_placeholder_before_authentication(self) -> None:
        environment = {
            "KARYO_PUBLIC_ORIGIN": "https://warehouse.example.test",
            "KARYO_KEYCLOAK_MAINTENANCE_URL": "http://127.0.0.1:8181/auth",
            "KARYO_KEYCLOAK_MAINTENANCE_USERNAME": "temporary-maintenance-admin",
            "KARYO_KEYCLOAK_MAINTENANCE_PASSWORD": "test-only-maintenance-password-4Nv8xQ",
            "KEYCLOAK_ADMIN_CLIENT_SECRET": "test-only-admin-client-secret-8Kp4zW",
            "OIDC_SECRET": "test-only-CHANGE_ME-backend-secret-3Qm8wL",
        }
        stderr = io.StringIO()
        with patch.dict(os.environ, environment, clear=True), patch.object(
            sys,
            "argv",
            [
                "migrate_keycloak_realm.py",
                "--apply",
                "--verified-backup",
                "--isolated-maintenance",
            ],
        ), patch.object(
            migration,
            "admin_token",
            side_effect=AssertionError("authentication must not run"),
        ), redirect_stderr(stderr):
            self.assertEqual(migration.main(), 1)
        self.assertIn("without CHANGE_ME placeholders", stderr.getvalue())
        self.assertIn("before any Keycloak write", stderr.getvalue())

    def test_legacy_identity_with_repurposed_state_is_refused(self) -> None:
        fingerprint = migration.LEGACY_USERS["admin"]

        class FakeApi:
            def __init__(
                self,
                client_roles: bool,
                federated: bool,
                added_credential: bool,
                added_consent: bool,
            ):
                self.client_roles = client_roles
                self.federated = federated
                self.added_credential = added_credential
                self.added_consent = added_consent

            def get(self, path: str):
                if "users?username=admin&exact=true" in path:
                    return [{"id": "legacy-admin"}]
                if path.endswith("/users/legacy-admin"):
                    return {
                        "id": "legacy-admin",
                        "username": "admin",
                        "enabled": True,
                        "email": fingerprint["email"],
                        "firstName": fingerprint["firstName"],
                        "lastName": fingerprint["lastName"],
                        "attributes": fingerprint["attributes"],
                    }
                if path.endswith("/role-mappings"):
                    client_mappings = {}
                    if self.client_roles:
                        client_mappings = {
                            "account": {
                                "id": "account-id",
                                "client": "account",
                                "mappings": [{"name": "manage-account"}],
                            }
                        }
                    return {
                        "realmMappings": [{"name": "ADMIN"}],
                        "clientMappings": client_mappings,
                    }
                if path.endswith("/groups"):
                    return []
                if path.endswith("/federated-identity"):
                    return [{"identityProvider": "corporate"}] if self.federated else []
                if path.endswith("/credentials"):
                    credentials = [{"id": "password-id", "type": "password"}]
                    if self.added_credential:
                        credentials.append({"id": "passkey-id", "type": "webauthn-passwordless"})
                    return credentials
                if path.endswith("/consents"):
                    return [{"clientId": "repurposed-client"}] if self.added_consent else []
                raise AssertionError(path)

        for client_roles, federated, added_credential, added_consent, expected in (
            (True, False, False, False, "client roles"),
            (False, True, False, False, "federated identities"),
            (False, False, True, False, "credentials expected one password"),
            (False, False, False, True, "client consents"),
        ):
            with self.subTest(expected=expected):
                with self.assertRaisesRegex(migration.MigrationError, expected):
                    migration.legacy_users(
                        FakeApi(client_roles, federated, added_credential, added_consent),
                        "https://identity.example.test",
                    )

    def test_token_drain_uses_every_client_page_and_the_longest_lifespan(self) -> None:
        clients = [{"attributes": {}} for _ in range(1001)]
        clients[-1] = {"attributes": {"access.token.lifespan": "1801"}}

        class FakeApi:
            def get(self, path: str):
                if path == "/admin/realms/karyo":
                    return {
                        "accessTokenLifespan": 300,
                        "accessTokenLifespanForImplicitFlow": 1800,
                    }
                if path.startswith("/admin/realms/karyo/clients?"):
                    query = parse_qs(urlsplit(path).query)
                    first = int(query["first"][0])
                    maximum = int(query["max"][0])
                    return clients[first:first + maximum]
                raise AssertionError(path)

        seconds, source = migration.access_token_lifespan(FakeApi())
        self.assertEqual(seconds, 1801)
        self.assertIn("access.token.lifespan", source)

    def test_blank_client_lifespan_attribute_inherits_the_realm_default(self) -> None:
        clients = [
            {"clientId": "karyo-web", "attributes": {"access.token.lifespan": ""}},
            {"clientId": "karyo-admin", "attributes": {"access.token.lifespan": "   "}},
            {"clientId": "karyo-backend", "attributes": {}},
        ]

        class FakeApi:
            def get(self, path: str):
                if path == "/admin/realms/karyo":
                    return {
                        "accessTokenLifespan": 300,
                        "accessTokenLifespanForImplicitFlow": 1800,
                    }
                if path.startswith("/admin/realms/karyo/clients?"):
                    query = parse_qs(urlsplit(path).query)
                    first = int(query["first"][0])
                    maximum = int(query["max"][0])
                    return clients[first:first + maximum]
                raise AssertionError(path)

        seconds, source = migration.access_token_lifespan(FakeApi())
        self.assertEqual(seconds, 1800)
        self.assertIn("realm accessTokenLifespan", source)

    def test_unparseable_client_lifespan_names_the_client_and_attribute(self) -> None:
        clients = [{"clientId": "karyo-web", "attributes": {"access.token.lifespan": "soon"}}]

        class FakeApi:
            def get(self, path: str):
                if path == "/admin/realms/karyo":
                    return {
                        "accessTokenLifespan": 300,
                        "accessTokenLifespanForImplicitFlow": 1800,
                    }
                if path.startswith("/admin/realms/karyo/clients?"):
                    query = parse_qs(urlsplit(path).query)
                    first = int(query["first"][0])
                    maximum = int(query["max"][0])
                    return clients[first:first + maximum]
                raise AssertionError(path)

        with self.assertRaises(migration.MigrationError) as raised:
            migration.access_token_lifespan(FakeApi())
        self.assertIn("karyo-web", str(raised.exception))
        self.assertIn("access.token.lifespan", str(raised.exception))

    def test_missing_node_is_a_toolchain_fault_not_a_configuration_fault(self) -> None:
        # A missing interpreter must never be reported as bad operator configuration: that sends
        # them to edit an env file that is not at fault. Distinct type AND distinct exit code.
        with patch.object(validator.shutil, "which", return_value=None):
            with self.assertRaises(validator.ToolchainError) as raised:
                validator.normalize_public_origin("https://wms.example.com")
        self.assertIn("Node.js is required", str(raised.exception))
        # Still a PublicOriginError, so existing callers keep catching it.
        self.assertIsInstance(raised.exception, validator.PublicOriginError)

        buffer = io.StringIO()
        with patch.object(validator.shutil, "which", return_value=None), redirect_stderr(buffer):
            status = validator.main(["validate_public_origin.py", "https://wms.example.com"])
        self.assertEqual(status, 3)
        self.assertIn("toolchain unavailable", buffer.getvalue())

    def test_migration_reports_missing_node_as_toolchain_not_bad_origin(self) -> None:
        # Fourth site of the same class: the migration must not blame KARYO_PUBLIC_ORIGIN for an
        # absent interpreter. ToolchainError subclasses PublicOriginError, so the except ordering
        # is what makes this correct - reverse them and the operator is sent to fix a good origin.
        argv = [
            "migrate_keycloak_realm.py",
            "--apply",
            "--verified-backup",
            "--isolated-maintenance",
        ]
        env = {"KARYO_PUBLIC_ORIGIN": "https://wms.example.com"}
        with patch.object(sys, "argv", argv), patch.dict(os.environ, env, clear=False), patch.object(
            validator.shutil, "which", return_value=None
        ):
            with self.assertRaises(migration.MigrationError) as raised:
                migration.migrate()
        message = str(raised.exception)
        self.assertIn("cannot validate KARYO_PUBLIC_ORIGIN", message)
        self.assertIn("Node.js is required", message)
        self.assertNotIn("invalid KARYO_PUBLIC_ORIGIN", message)

    def test_bad_origin_remains_a_configuration_fault(self) -> None:
        buffer = io.StringIO()
        with redirect_stderr(buffer):
            status = validator.main(
                ["validate_public_origin.py", "https://wms.example.com/path"]
            )
        self.assertEqual(status, 2)
        self.assertIn("invalid public URL configuration", buffer.getvalue())

    def _drain_api(self, client_lifespan: str):
        """Realm at 1800s plus one client carrying `client_lifespan`."""
        clients = [{"clientId": "karyo-web", "attributes": {"access.token.lifespan": client_lifespan}}]

        class FakeApi:
            def get(self, path: str):
                if path == "/admin/realms/karyo":
                    return {
                        "accessTokenLifespan": 1800,
                        "accessTokenLifespanForImplicitFlow": 300,
                    }
                if path.startswith("/admin/realms/karyo/clients?"):
                    query = parse_qs(urlsplit(path).query)
                    first = int(query["first"][0])
                    maximum = int(query["max"][0])
                    return clients[first:first + maximum]
                raise AssertionError(path)

        return FakeApi()

    def test_drain_within_the_ceiling_is_announced_before_any_mutation(self) -> None:
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            seconds = migration.plan_access_token_drain(
                self._drain_api("600"), migration.DRAIN_CEILING_SECONDS
            )
        self.assertEqual(seconds, 1800)
        announcement = buffer.getvalue()
        # The operator has to learn the outage length while they can still walk away, so the
        # duration and the source that set it are both stated up front.
        self.assertIn("1800", announcement)
        self.assertIn("realm accessTokenLifespan", announcement)

    def test_drain_beyond_the_ceiling_refuses_and_names_the_client(self) -> None:
        # NEGATIVE CASE: one client with a 24h lifespan is what makes the stack sit dead for a
        # day. The guard must stop here, before revoke_sessions and the irreversible deletes.
        buffer = io.StringIO()
        with self.assertRaises(migration.MigrationError) as raised, redirect_stdout(buffer):
            migration.plan_access_token_drain(
                self._drain_api("86400"), migration.DRAIN_CEILING_SECONDS
            )
        message = str(raised.exception)
        self.assertIn("86400", message)
        self.assertIn("karyo-web", message)
        self.assertIn("access.token.lifespan", message)
        self.assertIn("Nothing has been changed", message)
        self.assertIn("--max-drain-seconds", message)
        # It must refuse rather than announce-then-proceed.
        self.assertNotIn("Planned access-token drain", buffer.getvalue())

    def test_explicit_override_accepts_a_drain_beyond_the_default_ceiling(self) -> None:
        with redirect_stdout(io.StringIO()):
            seconds = migration.plan_access_token_drain(self._drain_api("86400"), 86400)
        self.assertEqual(seconds, 86400)

    def test_service_account_interactive_authentication_is_removed(self) -> None:
        credentials = [{"id": "password"}, {"id": "otp"}]
        identities = [{"identityProvider": "corporate oidc"}]

        class FakeApi:
            def get(self, path: str):
                if path.endswith("/credentials"):
                    return list(credentials)
                if path.endswith("/federated-identity"):
                    return list(identities)
                raise AssertionError(path)

            def delete(self, path: str, _body=None) -> None:
                if "/credentials/" in path:
                    credential_id = path.rsplit("/", 1)[1]
                    credentials[:] = [item for item in credentials if item["id"] != credential_id]
                    return
                if "/federated-identity/" in path:
                    identities.clear()
                    return
                raise AssertionError(path)

        migration.remove_service_user_authentication(FakeApi(), "service-user")
        self.assertEqual(credentials, [])
        self.assertEqual(identities, [])

    def test_service_client_reconciliation_leaves_human_users_and_callbacks_untouched(self) -> None:
        environment = {
            "KARYO_KEYCLOAK_MAINTENANCE_URL": "http://127.0.0.1:8181/auth",
            "KARYO_KEYCLOAK_MAINTENANCE_USERNAME": "temporary-maintenance-admin",
            "KARYO_KEYCLOAK_MAINTENANCE_PASSWORD": "test-only-maintenance-password-4Nv8xQ",
            "KEYCLOAK_ADMIN_CLIENT_SECRET": "test-only-admin-client-secret-8Kp4zW",
            "OIDC_SECRET": "test-only-rotated-backend-secret-3Qm8wL",
        }
        api = migration.KeycloakAdminApi("http://127.0.0.1:8181/auth", "initial-token")

        def mark_write(*_args, **_kwargs):
            api.write_attempted = True

        with patch.dict(os.environ, environment, clear=True), patch.object(
            sys,
            "argv",
            [
                "migrate_keycloak_realm.py",
                "--reconcile-service-clients",
                "--isolated-maintenance",
            ],
        ), patch.object(
            migration,
            "admin_token",
            side_effect=("initial-token", "refreshed-token"),
        ) as token_mock, patch.object(
            migration,
            "KeycloakAdminApi",
            return_value=api,
        ), patch.object(
            migration,
            "client",
            side_effect=lambda _api, client_id: {"id": f"{client_id}-id", "clientId": client_id},
        ), patch.object(
            migration,
            "access_token_lifespan",
            return_value=(900, "realm accessTokenLifespan"),
        ), patch.object(
            migration,
            "configure_admin_client",
            side_effect=lambda *_args: (
                mark_write(),
                {"id": "admin-id", "clientId": migration.ADMIN_CLIENT_ID},
            )[1],
        ), patch.object(
            migration,
            "configure_backend_client",
            return_value={"id": "backend-id", "clientId": migration.BACKEND_CLIENT_ID},
        ), patch.object(
            migration,
            "set_service_roles",
            side_effect=({"manage-users", "view-realm"}, {"view-events", "view-users"}),
        ), patch.object(
            migration,
            "client_credentials_grant",
        ) as grant_mock, patch.object(
            migration,
            "revoke_sessions",
            side_effect=mark_write,
        ) as revoke_mock, patch.object(
            migration,
            "drain_access_tokens",
        ) as drain_mock, patch.object(
            migration,
            "configure_web_client",
        ) as web_mock, patch.object(
            migration,
            "legacy_users",
        ) as legacy_mock:
            self.assertEqual(migration.migrate(), 0)

        self.assertEqual(token_mock.call_count, 2)
        self.assertEqual(api.token, "refreshed-token")
        self.assertEqual(grant_mock.call_count, 4)
        revoke_mock.assert_called_once_with(api, [])
        drain_mock.assert_called_once_with(900)
        web_mock.assert_not_called()
        legacy_mock.assert_not_called()

    def test_migration_drains_and_refreshes_without_legacy_users(self) -> None:
        environment = {
            "KARYO_PUBLIC_ORIGIN": "https://warehouse.example.test",
            "KARYO_KEYCLOAK_MAINTENANCE_URL": "http://127.0.0.1:8181/auth",
            "KARYO_KEYCLOAK_MAINTENANCE_USERNAME": "temporary-maintenance-admin",
            "KARYO_KEYCLOAK_MAINTENANCE_PASSWORD": "test-only-maintenance-password-4Nv8xQ",
            "KEYCLOAK_ADMIN_CLIENT_SECRET": "test-only-admin-client-secret-8Kp4zW",
            "OIDC_SECRET": "test-only-rotated-backend-secret-3Qm8wL",
        }
        api = migration.KeycloakAdminApi("http://127.0.0.1:8181/auth", "initial-token")

        def mark_write(*_args, **_kwargs):
            api.write_attempted = True

        with patch.dict(os.environ, environment, clear=True), patch.object(
            sys,
            "argv",
            [
                "migrate_keycloak_realm.py",
                "--apply",
                "--verified-backup",
                "--isolated-maintenance",
            ],
        ), patch.object(
            migration,
            "admin_token",
            side_effect=("initial-token", "refreshed-token"),
        ) as token_mock, patch.object(
            migration,
            "KeycloakAdminApi",
            return_value=api,
        ), patch.object(
            migration,
            "client",
            return_value={"id": "web-id"},
        ), patch.object(
            migration,
            "legacy_users",
            return_value=[],
        ), patch.object(
            migration,
            "access_token_lifespan",
            return_value=(900, "realm accessTokenLifespan"),
        ), patch.object(
            migration,
            "configure_web_client",
            side_effect=mark_write,
        ), patch.object(
            migration,
            "configure_admin_client",
            return_value={"id": "admin-id", "clientId": migration.ADMIN_CLIENT_ID},
        ), patch.object(
            migration,
            "configure_backend_client",
            return_value={"id": "backend-id", "clientId": migration.BACKEND_CLIENT_ID},
        ), patch.object(
            migration,
            "set_service_roles",
        ), patch.object(
            migration,
            "client_credentials_grant",
        ), patch.object(
            migration,
            "revoke_sessions",
        ) as revoke_mock, patch.object(
            migration,
            "drain_access_tokens",
        ) as drain_mock, patch.object(
            api,
            "delete",
            side_effect=mark_write,
        ), patch.object(
            migration,
            "verify",
        ) as verify_mock:
            self.assertEqual(migration.migrate(), 0)

        self.assertEqual(token_mock.call_count, 2)
        self.assertEqual(api.token, "refreshed-token")
        revoke_mock.assert_called_once_with(api, [])
        drain_mock.assert_called_once_with(900)
        self.assertTrue(api.write_attempted)
        verify_mock.assert_called_once_with(api, "https://warehouse.example.test")

    def test_remote_e2e_target_requires_https_before_provisioning(self) -> None:
        project_root = Path(__file__).resolve().parent.parent
        target = "http://remote.example.test"
        environment = dict(os.environ)
        environment.update(
            {
                "KARYO_E2E_ALLOW_REMOTE_PROVISION": "true",
                "KARYO_E2E_EXPECTED_ORIGIN": target,
            }
        )
        result = subprocess.run(
            [
                "node",
                "--no-warnings",
                "--experimental-strip-types",
                str(
                    project_root
                    / "tests"
                    / "e2e"
                    / "fixtures"
                    / "provisioning-target-cli.ts"
                ),
                target,
            ],
            cwd=project_root,
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("without HTTPS", result.stderr)

    def test_unavailable_remote_e2e_target_never_starts_local_stack(self) -> None:
        project_root = Path(__file__).resolve().parent.parent
        temp_root = project_root / "build" / "tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            bin_dir = Path(directory)
            curl = bin_dir / "curl"
            curl.write_text("#!/bin/sh\nexit 7\n", encoding="utf-8")
            curl.chmod(0o700)
            environment = dict(os.environ)
            environment.update(
                {
                    "BASE_URL": "https://unavailable.example.test",
                    "KARYO_E2E_ALLOW_REMOTE_PROVISION": "true",
                    "KARYO_E2E_EXPECTED_ORIGIN": "https://unavailable.example.test",
                    "PATH": f"{bin_dir}:{environment['PATH']}",
                }
            )
            result = subprocess.run(
                ["bash", str(project_root / "scripts" / "run-e2e.sh")],
                cwd=project_root,
                env=environment,
                capture_output=True,
                text=True,
                timeout=20,
            )
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1)
        self.assertIn("Remote E2E target is unavailable", output)
        self.assertNotIn("starting it", output)

    def test_remote_e2e_target_requires_explicit_admin_client_secret(self) -> None:
        project_root = Path(__file__).resolve().parent.parent
        temp_root = project_root / "build" / "tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            bin_dir = Path(directory)
            curl = bin_dir / "curl"
            curl.write_text("#!/bin/sh\nprintf '200'\n", encoding="utf-8")
            curl.chmod(0o700)
            environment = dict(os.environ)
            environment.pop("KEYCLOAK_ADMIN_CLIENT_SECRET", None)
            environment.update(
                {
                    "BASE_URL": "https://available.example.test",
                    "KARYO_E2E_ALLOW_REMOTE_PROVISION": "true",
                    "KARYO_E2E_EXPECTED_ORIGIN": "https://available.example.test",
                    "PATH": f"{bin_dir}:{environment['PATH']}",
                }
            )
            result = subprocess.run(
                ["bash", str(project_root / "scripts" / "run-e2e.sh")],
                cwd=project_root,
                env=environment,
                capture_output=True,
                text=True,
                timeout=20,
            )
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1)
        self.assertIn(
            "Remote E2E provisioning requires KEYCLOAK_ADMIN_CLIENT_SECRET to be supplied explicitly",
            output,
        )

    def test_deploy_validates_supplied_bootstrap_credentials_in_every_state(self) -> None:
        project_root = Path(__file__).resolve().parent.parent
        temp_root = project_root / "build" / "tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            env_file = Path(directory) / "env.prod"
            base_environment = (
                "DB_USERNAME=karyo\n"
                "DB_PASSWORD=V7mQ2xR9pL4nT8cK\n"
                "POSTGRES_USER=karyo\n"
                "POSTGRES_PASSWORD=V7mQ2xR9pL4nT8cK\n"
                "KC_DB_USERNAME=karyo\n"
                "KC_DB_PASSWORD=V7mQ2xR9pL4nT8cK\n"
                "KEYCLOAK_ADMIN_CLIENT_SECRET=A7mQ2xR9pL4nT8cK6vW3zB5dH1jF0sNq\n"
                "KARYO_PUBLIC_ORIGIN=https://warehouse.example.test\n"
                "KARYO_DOMAIN=warehouse.example.test\n"
                "KC_HOSTNAME=https://warehouse.example.test/auth\n"
                "OIDC_SECRET=Q4nR8tY2uI6oP0aS3dF7gH1jK5lZ9xCv\n"
            )
            env_file.write_text(base_environment)
            env_file.chmod(0o600)

            def validate() -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    [
                        "bash",
                        str(project_root / "scripts" / "deploy-server.sh"),
                        "--validate-env",
                        str(env_file),
                    ],
                    cwd=project_root,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    check=False,
                )

            self.assertEqual(validate().returncode, 0)
            env_file.write_text(
                base_environment
                + "KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin\n"
                + "KC_BOOTSTRAP_ADMIN_PASSWORD=CHANGE_ME_bootstrap_password\n"
            )
            rejected = validate()
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("KC_BOOTSTRAP_ADMIN_PASSWORD (empty or contains CHANGE_ME)", rejected.stdout)

    def test_partial_failure_output_names_rollback(self) -> None:
        stderr = io.StringIO()
        with patch.object(
            migration,
            "migrate",
            side_effect=migration.PartialMigrationError("response lost"),
        ), redirect_stderr(stderr):
            self.assertEqual(migration.main(), 1)
        self.assertIn("REALM MIGRATION MAY BE PARTIALLY APPLIED", stderr.getvalue())
        self.assertIn("Roll Back a Failed Realm Migration", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
