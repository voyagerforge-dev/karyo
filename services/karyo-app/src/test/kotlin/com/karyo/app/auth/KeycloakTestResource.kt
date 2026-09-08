package com.karyo.app.auth

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Boots a real Keycloak 26 importing `infrastructure/keycloak/karyo-realm.json` (the realm the
 * dev stacks mount) and points `quarkus.oidc.auth-server-url` at it. Two tests need that:
 * [KeycloakEventPollerTest] exercises the genuine admin Events API - password-grant logins, the
 * `karyo-backend` service account's `view-events` grant, event JSON, all real - and
 * [RealmPrincipalKindClaimTest] asserts the realm actually issues the `principal_kind` claim that
 * decides read scoping.
 *
 * The default test config points OIDC at `localhost:8180`, where no Keycloak runs under
 * plain `./gradlew test` (every other test mocks auth via `@TestSecurity`, so nothing else
 * notices). This resource is restricted to its annotated classes, so only those two tests pay
 * the container-boot cost. The realm file is copied into the container (no bind mount), so
 * rootless-Podman SELinux labeling never gets involved.
 */
class KeycloakTestResource : QuarkusTestResourceLifecycleManager {

    private lateinit var keycloak: GenericContainer<*>

    override fun start(): Map<String, String> {
        keycloak = GenericContainer("quay.io/keycloak/keycloak:26.0")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", BOOTSTRAP_ADMIN_USERNAME)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", BOOTSTRAP_ADMIN_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(realmFile()),
                "/opt/keycloak/data/import/karyo-realm.json",
            )
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(KC_PORT)
            // 200 on the realm endpoint proves the import ran, not just that HTTP is up.
            .waitingFor(
                Wait.forHttp("/realms/$REALM").forPort(KC_PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
        keycloak.start()
        val baseUrl = "http://${keycloak.host}:${keycloak.getMappedPort(KC_PORT)}"
        return mapOf(
            "quarkus.oidc.auth-server-url" to "$baseUrl/realms/$REALM",
            // Server root (not the realm URL) so tests can reach /admin/realms/... and the
            // master realm, neither of which hangs off quarkus.oidc.auth-server-url.
            BASE_URL_PROPERTY to baseUrl,
            // The quarkus-keycloak-admin-client extension (what UserManagementService's injected
            // `Keycloak` comes from) is configured by its OWN property, not by
            // quarkus.oidc.auth-server-url. Left alone it keeps the production default
            // ${KEYCLOAK_URL:http://localhost:8180} -- the fixed port compose-devservices.yml maps
            // and that nothing listens on during a test run -- so any test that actually reaches
            // the Admin API dies with "Connection refused: localhost/127.0.0.1:8180". Point it at
            // this container's server root. Note this is a TEST-only override: the property keeps
            // its production default in application.yaml.
            "quarkus.keycloak.admin-client.server-url" to baseUrl,
        )
    }

    override fun stop() {
        if (this::keycloak.isInitialized) keycloak.stop()
    }

    /** Walks up from the test working directory to the repo root's realm file. */
    private fun realmFile(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("infrastructure/keycloak/karyo-realm.json")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("infrastructure/keycloak/karyo-realm.json not found above ${Paths.get("").toAbsolutePath()}")
    }

    companion object {
        private const val KC_PORT = 8080

        /** Realm imported from `infrastructure/keycloak/karyo-realm.json`. */
        const val REALM = "karyo"

        /** Config key carrying the container's server root, set by [start]. */
        const val BASE_URL_PROPERTY = "karyo.test.keycloak.base-url"

        const val BOOTSTRAP_ADMIN_USERNAME = "admin"
        const val BOOTSTRAP_ADMIN_PASSWORD = "admin"
    }
}
