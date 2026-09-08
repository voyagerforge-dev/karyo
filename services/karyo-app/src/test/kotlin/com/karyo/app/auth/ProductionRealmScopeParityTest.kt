package com.karyo.app.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * An upgraded realm and a first-deploy realm are both supported production states, so the service
 * identities they end with must be interchangeable.
 *
 * The legacy fixture strips `defaultClientScopes`, reproducing a realm imported before the
 * production realm declared them, so Keycloak assigns its own built-in defaults there. Comparing
 * the migrated realm against a fresh import therefore proves two things at once: the migration
 * does not curate scopes away, and the set the production realm now declares is the set Keycloak
 * grants on its own. A migration that strips everything but `roles` drops the `basic` scope, and
 * with it the `sub` claim that karyo-admin's Admin REST API calls depend on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionRealmScopeParityTest {

    private val mapper = ObjectMapper()

    @Test
    fun `migrated service clients keep the client scopes a fresh production import grants`() {
        val projectRoot = projectRoot()
        val productionRealm = projectRoot.resolve("infrastructure/keycloak/karyo-realm-prod.json")

        val declared = declaredServiceClientScopes(productionRealm)

        val migrated = keycloak(legacyRealmFile(projectRoot, productionRealm))
        val fresh = keycloak(productionRealm)
        try {
            Startables.deepStart(migrated, fresh).join()
            runMigration(projectRoot, baseUrl(migrated))
            assertThat(serviceClientScopes(baseUrl(fresh)))
                .`as`("a fresh import must realize the scopes the production realm declares")
                .isEqualTo(declared)
            assertThat(serviceClientScopes(baseUrl(migrated)))
                .`as`("a migrated realm must end with the scopes a fresh import grants")
                .isEqualTo(declared)
        } finally {
            fresh.stop()
            migrated.stop()
        }
    }

    private fun keycloak(realmFile: Path): GenericContainer<*> =
        GenericContainer("quay.io/keycloak/keycloak:26.0")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", MAINTENANCE_USERNAME)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", MAINTENANCE_PASSWORD)
            .withEnv("OIDC_SECRET", OIDC_SECRET)
            .withEnv("KEYCLOAK_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv("KARYO_PUBLIC_ORIGIN", PUBLIC_ORIGIN)
            .withEnv("JAVA_OPTS_APPEND", "-Dkeycloak.import.replace-placeholders=true")
            .withCopyFileToContainer(
                MountableFile.forHostPath(realmFile),
                "/opt/keycloak/data/import/karyo-realm.json",
            )
            .withCommand("start-dev", "--http-relative-path=/auth", "--import-realm")
            .withExposedPorts(KEYCLOAK_PORT)
            .waitingFor(
                Wait.forHttp("/auth/realms/$REALM").forPort(KEYCLOAK_PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )

    private fun baseUrl(container: GenericContainer<*>): String =
        "http://${container.host}:${container.getMappedPort(KEYCLOAK_PORT)}/auth"

    private fun legacyRealmFile(projectRoot: Path, source: Path): Path {
        val realm = mapper.readTree(Files.readString(source)) as ObjectNode
        realm.put("accessTokenLifespan", 1)
        realm.put("accessTokenLifespanForImplicitFlow", 2)
        realm.withArray("clients").forEach {
            (it as ObjectNode).remove(listOf("defaultClientScopes", "optionalClientScopes"))
        }
        val target = projectRoot.resolve(
            "services/karyo-app/build/tmp/production-realm-scope-parity/legacy-realm.json",
        )
        Files.createDirectories(target.parent)
        Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(realm))
        return target
    }

    private fun runMigration(projectRoot: Path, baseUrl: String) {
        val process = ProcessBuilder(
            "python3",
            projectRoot.resolve("scripts/migrate_keycloak_realm.py").toString(),
            "--apply",
            "--isolated-maintenance",
            "--verified-backup",
        )
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["KARYO_PUBLIC_ORIGIN"] = PUBLIC_ORIGIN
                environment()["KARYO_KEYCLOAK_MAINTENANCE_URL"] = baseUrl
                environment()["KARYO_KEYCLOAK_MAINTENANCE_USERNAME"] = MAINTENANCE_USERNAME
                environment()["KARYO_KEYCLOAK_MAINTENANCE_PASSWORD"] = MAINTENANCE_PASSWORD
                environment()["KEYCLOAK_ADMIN_CLIENT_SECRET"] = ADMIN_CLIENT_SECRET
                environment()["OIDC_SECRET"] = ROTATED_OIDC_SECRET
            }
            .start()
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.exitValue()).withFailMessage(output).isZero()
    }

    private fun declaredServiceClientScopes(realmFile: Path): Map<String, Map<String, Set<String>>> {
        val clients = mapper.readTree(Files.readString(realmFile)).path("clients")
        return listOf(BACKEND_CLIENT_ID, ADMIN_CLIENT_ID).associateWith { clientId ->
            val declaring = clients.first { it.path("clientId").asText() == clientId }
            mapOf(
                "default-client-scopes" to declaredScopes(declaring, clientId, "defaultClientScopes"),
                "optional-client-scopes" to
                    declaredScopes(declaring, clientId, "optionalClientScopes"),
            )
        }
    }

    private fun declaredScopes(client: JsonNode, clientId: String, field: String): Set<String> {
        val scopes = client.path(field)
        require(scopes.isArray && !scopes.isEmpty) {
            "the production realm must declare $field on $clientId"
        }
        return scopes.map(JsonNode::asText).toSet()
    }

    private fun serviceClientScopes(baseUrl: String): Map<String, Map<String, Set<String>>> {
        val api = adminApi(baseUrl)
        return listOf(BACKEND_CLIENT_ID, ADMIN_CLIENT_ID).associateWith { clientId ->
            val uuid = api()
                .queryParam("clientId", clientId)
                .get("/admin/realms/$REALM/clients")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("[0].id")
            listOf("default-client-scopes", "optional-client-scopes").associateWith { kind ->
                api()
                    .get("/admin/realms/$REALM/clients/$uuid/$kind")
                    .then()
                    .statusCode(200)
                    .extract()
                    .`as`(Array<JsonNode>::class.java)
                    .map { it.path("name").asText() }
                    .toSet()
            }
        }
    }

    private fun adminApi(baseUrl: String): () -> RequestSpecification = {
        val token = KeycloakTestGrants.passwordGrant(
            realmUrl = "$baseUrl/realms/master",
            clientId = "admin-cli",
            clientSecret = null,
            username = MAINTENANCE_USERNAME,
            password = MAINTENANCE_PASSWORD,
        ).then().statusCode(200).extract().path<String>("access_token")
        given().baseUri(baseUrl).header("Authorization", "Bearer $token")
    }

    private fun projectRoot(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            if (Files.exists(directory.resolve("infrastructure/keycloak/karyo-realm-prod.json"))) {
                return directory
            }
            directory = directory.parent
        }
        error("project root not found")
    }

    private companion object {
        const val KEYCLOAK_PORT = 8080
        const val REALM = "karyo"
        const val ADMIN_CLIENT_ID = "karyo-admin"
        const val BACKEND_CLIENT_ID = "karyo-backend"
        const val PUBLIC_ORIGIN = "https://warehouse.example.test"
        const val OIDC_SECRET = "test-only-production-client-secret-9Hj4vT"
        const val ROTATED_OIDC_SECRET = "test-only-rotated-backend-secret-3Qm8wL"
        const val ADMIN_CLIENT_SECRET = "test-only-admin-client-secret-8Kp4zW"
        const val MAINTENANCE_USERNAME = "temporary-maintenance-admin"
        const val MAINTENANCE_PASSWORD = "test-only-maintenance-password-4Nv8xQ"
    }
}
