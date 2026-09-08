package com.karyo.app.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The production compose file is the deployment contract the container runtime consumes verbatim.
 *
 * An ordinary deployment must reach Keycloak only through nginx: publishing the admin port on the
 * deploy host would expose `/auth/admin` and the Admin REST API to every local process, bypassing
 * nginx's credential-redacting access log and its 404 on internal prefixes. The loopback port the
 * realm migration needs belongs in `docker-compose.maintenance.yml`, which the DEPLOY.md runbooks
 * layer on for the maintenance window and take down again.
 */
class ProductionComposeExposureTest {

    @Test
    fun `production deployment publishes no keycloak host port`() {
        val services = composeServices("docker-compose.prod.yml")

        assertThat(publishedPorts(services, "keycloak")).isEmpty()
        assertThat(services.keys.filter { publishedPorts(services, it).isNotEmpty() })
            .containsExactly("nginx")
    }

    @Test
    fun `the maintenance override publishes keycloak on loopback only`() {
        val services = composeServices("docker-compose.maintenance.yml")

        assertThat(services.keys).containsExactly("keycloak")
        assertThat(publishedPorts(services, "keycloak"))
            .containsExactly("127.0.0.1:\${KARYO_KEYCLOAK_MAINTENANCE_PORT:-8181}:8080")
    }

    private fun publishedPorts(services: Map<String, Any?>, service: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val definition = services[service] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        return (definition["ports"] as? List<Any?>).orEmpty().map(Any?::toString)
    }

    private fun composeServices(name: String): Map<String, Any?> {
        val file = projectRoot().resolve("infrastructure/docker").resolve(name)
        @Suppress("UNCHECKED_CAST")
        val compose = Yaml().load<Map<String, Any?>>(Files.readString(file))
        @Suppress("UNCHECKED_CAST")
        return compose["services"] as Map<String, Any?>
    }

    private fun projectRoot(): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            if (Files.exists(directory.resolve("infrastructure/docker/docker-compose.prod.yml"))) {
                return directory
            }
            directory = directory.parent
        }
        error("project root not found")
    }
}
