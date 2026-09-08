package com.karyo.webhooks.relay

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpResult(val code: Int, val error: String?)

@ApplicationScoped
class WebhookHttpClient(
    @ConfigProperty(name = "karyo.webhooks.http-timeout", defaultValue = "10s")
    private val timeout: Duration,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER) // Fix 1: no redirect-following (SSRF mitigation)
        .build()

    fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() in 200..299) HttpResult(resp.statusCode(), null)
            else HttpResult(resp.statusCode(), "HTTP ${resp.statusCode()}")
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt() // Fix 3: restore interrupt
            HttpResult(0, e.message ?: e.javaClass.simpleName)
        }
}
