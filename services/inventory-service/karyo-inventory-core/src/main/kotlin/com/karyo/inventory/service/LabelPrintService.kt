package com.karyo.inventory.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw ZPL push to a label printer over TCP :9100 (the label-industry standard; the printer is
 * its own renderer, so there is no driver and no spool). Fail-fast by design: a floor operator
 * standing at the printer needs the error now; a spooler printing queued labels later creates
 * mislabeling risk. Single printer per instance in v1; `KARYO_PRINT_URL` uses the non-empty
 * sentinel "none" (SRCFG00040: an injected @ConfigProperty String must never default to "").
 */
@ApplicationScoped
class LabelPrintService(
    @ConfigProperty(name = "karyo.print.url", defaultValue = "none")
    private val printUrl: String,
) {
    class NoPrinterConfigured : RuntimeException("no printer configured (KARYO_PRINT_URL)")
    class PrinterUnreachable(target: String, cause: Throwable) :
        RuntimeException("printer unreachable at $target", cause)

    fun print(zpl: String) {
        if (printUrl == "none" || printUrl.isBlank()) throw NoPrinterConfigured()
        printTo(printUrl, zpl)
    }

    /**
     * Direct-target variant: bypasses the configured `karyo.print.url` and pushes straight to
     * [target]. Public because it is exercised directly by tests (a different Gradle module,
     * :services:karyo-app) and is the seam the future printer registry (per-request printer
     * selection) will call into.
     */
    fun printTo(target: String, zpl: String) {
        val host: String
        val port: Int
        if (target.contains(':')) {
            host = target.substringBeforeLast(':')
            port = target.substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT
        } else {
            host = target
            port = DEFAULT_PORT
        }
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().use { it.write(zpl.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: IOException) {
            throw PrinterUnreachable("$host:$port", e)
        }
    }

    companion object {
        private const val DEFAULT_PORT = 9100
        private const val CONNECT_TIMEOUT_MS = 3000
    }
}
