package com.karyo.inventory.service

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

@QuarkusTest
class LabelPrintServiceTest {

    @Inject
    lateinit var service: LabelPrintService

    @Test
    fun `sends zpl bytes to the configured printer`() {
        val received = StringBuilder()
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s -> received.append(s.getInputStream().readBytes().toString(Charsets.UTF_8)) }
            }
            service.printTo("localhost:${server.localPort}", "^XA^FDtest^FS^XZ")
            t.join(3000)
        }
        assertEquals("^XA^FDtest^FS^XZ", received.toString())
    }

    @Test
    fun `unconfigured printer raises NoPrinterConfigured`() {
        // default test config leaves karyo.print.url at the "none" sentinel
        assertThrows(LabelPrintService.NoPrinterConfigured::class.java) { service.print("^XA^XZ") }
    }

    @Test
    fun `unreachable printer raises PrinterUnreachable`() {
        // port 1 is never listening
        assertThrows(LabelPrintService.PrinterUnreachable::class.java) { service.printTo("localhost:1", "^XA^XZ") }
    }
}
