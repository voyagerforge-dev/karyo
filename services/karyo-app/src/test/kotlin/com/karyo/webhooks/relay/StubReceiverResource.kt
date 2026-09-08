package com.karyo.webhooks.relay

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Test-only receiver. /test-receiver/ok always 200; /test-receiver/fail always 500; records last headers. */
@Path("/test-receiver")
@ApplicationScoped
class StubReceiverResource {
    val calls = AtomicInteger(0)
    val headers = ConcurrentHashMap<String, String>()

    @POST @Path("/ok") @Consumes(MediaType.APPLICATION_JSON)
    fun ok(@Context h: HttpHeaders, body: String): Response {
        calls.incrementAndGet()
        h.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.firstOrNull() ?: "" }
        return Response.ok().build()
    }

    @POST @Path("/fail") @Consumes(MediaType.APPLICATION_JSON)
    fun fail(body: String): Response = Response.status(500).build()
}
