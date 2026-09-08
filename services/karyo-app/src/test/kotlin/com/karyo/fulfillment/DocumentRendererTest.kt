package com.karyo.fulfillment

import com.karyo.documents.DocumentRenderer
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class DocumentRendererTest {

    @Inject lateinit var renderer: DocumentRenderer

    @Test
    fun `renders a classpath template to HTML and then to a PDF`() {
        val html = renderer.render("/templates/packing-slip.html", mapOf(
            "orderNumber" to "DO-1", "shipmentNumber" to "SHP-1", "date" to "2026-06-15",
            "shipTo" to mapOf("customerName" to "Acme", "street" to "5 Main", "streetNumber" to "",
                "city" to "Springfield", "zipCode" to "12345", "country" to "US"),
            // Sprint C, Task 5: the slip's "units" list was wrapped in per-order "orders"
            // sections -- see ShipmentDocumentService.orderSections's KDoc for the "why".
            "orders" to listOf(mapOf(
                "orderNumber" to "DO-1",
                "units" to listOf(mapOf(
                    "positionIndex" to 1, "unitNumber" to "SU1",
                    "lines" to listOf(mapOf("sku" to "SKU-7", "qty" to "60")),
                )),
            )),
        ))
        assertThat(html).contains("Acme").contains("SKU-7")
        val pdf = renderer.htmlToPdf(html)
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
    }

    @Test
    fun `renders a ZPL text template`() {
        val zpl = renderer.render("/templates/label.zpl", mapOf(
            "shipmentNumber" to "SHP-1", "shippingUnitNumber" to "SHP-1-SU1", "trackingNumber" to "MAN-SHP-1",
            "shipTo" to mapOf("customerName" to "Acme", "street" to "5 Main", "streetNumber" to "",
                "city" to "Springfield", "zipCode" to "12345", "country" to "US"),
            "positionIndex" to 1, "totalUnits" to 1,
        ))
        assertThat(zpl).contains("MAN-SHP-1").contains("^XA")
    }

    @Test
    fun `html template escapes interpolated values (no injection)`() {
        val html = renderer.render("/templates/packing-slip.html", mapOf(
            "orderNumber" to "DO-1", "shipmentNumber" to "SHP-1", "date" to "2026-06-15",
            "shipTo" to mapOf("customerName" to "<img src=x onerror=alert(1)>", "street" to "", "streetNumber" to "",
                "city" to "", "zipCode" to "", "country" to ""),
            "orders" to listOf(mapOf(
                "orderNumber" to "DO-1",
                "units" to listOf(mapOf(
                    "positionIndex" to 1, "unitNumber" to "SU1",
                    "lines" to emptyList<Map<String, Any?>>(),
                )),
            )),
        ))
        // the dangerous markup must be escaped, not present as a live tag
        assertThat(html).doesNotContain("<img src=x")
        assertThat(html).contains("&lt;img src=x")
    }

    @Test
    fun `zpl template strips control chars from interpolated values`() {
        val zpl = renderer.render("/templates/label.zpl", mapOf(
            "shipmentNumber" to "SHP-1", "shippingUnitNumber" to "SHP-1-SU1", "trackingNumber" to "MAN-SHP-1",
            "shipTo" to mapOf("customerName" to "Acme^XZInjected", "street" to "", "streetNumber" to "",
                "city" to "", "zipCode" to "", "country" to ""),
            "positionIndex" to 1, "totalUnits" to 1,
        ))
        // the injected ^XZ end-format command must not survive in the label stream
        assertThat(zpl).doesNotContain("Acme^XZ")
        assertThat(zpl).contains("AcmeXZInjected")
    }
}
