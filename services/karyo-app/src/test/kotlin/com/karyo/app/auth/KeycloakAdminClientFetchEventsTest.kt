package com.karyo.app.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Row 27 (defect-burndown-4): a stubbed HTTP layer standing in for Keycloak's admin Events
 * API, proving [KeycloakAdminClient.fetchEvents]'s `first`/`max` paging loop and the
 * [KcEventBatch.truncated] cap-hit signal. [KeycloakEventPollerTest] already covers the
 * real-Keycloak integration (dedup, attribution, backdating) end to end; reproducing the
 * page-cap path there would mean generating 5000+ real logins against a Testcontainers
 * Keycloak, which is not a cheap or honest way to test arithmetic. A plain JDK [HttpServer]
 * stands in for Keycloak instead: [KeycloakAdminClient] talks to it over a real
 * [java.net.http.HttpClient] round trip (nothing about the client is mocked), so this
 * exercises the actual request-building/pagination code, not a reimplementation of it.
 */
class KeycloakAdminClientFetchEventsTest {

    private var server: HttpServer? = null
    private val eventRequestCount = AtomicInteger(0)

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    /**
     * Starts the stub and returns a [KeycloakAdminClient] pointed at it, backed by [count]
     * synthetic LOGIN events newest-first: event `i` (0-based, `i` = 0 is newest) has
     * `time = START_TIME - i`, one ms apart.
     */
    private fun startStub(count: Int): KeycloakAdminClient {
        val times = (0 until count).map { i -> START_TIME - i }
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/realms/test/protocol/openid-connect/token") { exchange ->
            val body = """{"access_token":"stub-token","expires_in":300}"""
            respond(exchange, body)
        }
        httpServer.createContext("/admin/realms/test/events") { exchange ->
            eventRequestCount.incrementAndGet()
            val params = (exchange.requestURI.query ?: "")
                .split("&")
                .filter { it.contains("=") }
                .associate { kv -> kv.substringBefore("=") to kv.substringAfter("=") }
            val first = params["first"]?.toIntOrNull() ?: 0
            val max = params["max"]?.toIntOrNull() ?: 200
            val page = times.drop(first).take(max)
            val body = page.joinToString(prefix = "[", postfix = "]", separator = ",") { time ->
                """{"time":$time,"type":"LOGIN","userId":"u","sessionId":"s",""" +
                    """"ipAddress":"127.0.0.1","details":{"username":"u"}}"""
            }
            respond(exchange, body)
        }
        httpServer.start()
        server = httpServer
        val port = httpServer.address.port
        return KeycloakAdminClient(
            authServerUrl = "http://localhost:$port/realms/test",
            clientId = "test-client",
            clientSecret = "test-secret",
            objectMapper = ObjectMapper(),
        )
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `pages accumulate across multiple requests and the loop stops on a short page`() {
        val client = startStub(count = 250) // page0 full (200), page1 short (50)
        val batch = client.fetchEvents(afterMs = 0)

        assertThat(batch.events).hasSize(250)
        assertThat(batch.truncated).isFalse()
        assertThat(eventRequestCount.get()).isEqualTo(2)
    }

    @Test
    fun `loop stops once a page's oldest event is older than the cursor, without reaching the cap`() {
        val client = startStub(count = 1000)
        // page0 = times [START, START-199]; page1 = [START-200, START-399]. This afterMs falls
        // inside page1's range, so page1's oldest (last) entry is already past the cursor.
        val afterMs = START_TIME - 300
        val batch = client.fetchEvents(afterMs)

        assertThat(eventRequestCount.get()).`as`("must stop after page1, never requesting page2").isEqualTo(2)
        assertThat(batch.truncated).isFalse()
        assertThat(batch.events).isNotEmpty()
        assertThat(batch.events).allMatch { it.time >= afterMs }
    }

    @Test
    fun `hitting the 25-page hard cap marks the batch truncated`() {
        val client = startStub(count = 25 * 200) // every page exactly full: the cap trips first
        val batch = client.fetchEvents(afterMs = 0)

        assertThat(eventRequestCount.get()).isEqualTo(25)
        assertThat(batch.truncated).isTrue()
        assertThat(batch.events).hasSize(25 * 200)
    }

    companion object {
        private const val START_TIME = 10_000_000L
    }
}
