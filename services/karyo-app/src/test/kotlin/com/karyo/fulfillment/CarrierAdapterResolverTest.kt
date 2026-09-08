package com.karyo.fulfillment

import com.karyo.fulfillment.service.CarrierAdapterResolver
import com.karyo.fulfillment.spi.CarrierAdapter
import com.karyo.fulfillment.spi.CarrierAssignment
import com.karyo.fulfillment.spi.ManifestRequest
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Test-scoped adapter that only handles FEDEX at a priority lower than ManualCarrierAdapter's
 * Int.MAX_VALUE, so it wins for FEDEX while UPS/MANUAL still fall through to Manual.
 */
@ApplicationScoped
class StubFedExCarrierAdapter : CarrierAdapter {
    override val priority: Int = 100
    override fun handles(carrierName: String): Boolean = carrierName == "FEDEX"
    override fun manifest(request: ManifestRequest): CarrierAssignment =
        CarrierAssignment(request.carrierName, request.carrierService, "FX-${request.shipmentNumber}")
}

@QuarkusTest
class CarrierAdapterResolverTest {

    @Inject lateinit var resolver: CarrierAdapterResolver

    private fun req(tracking: String?) = ManifestRequest(
        shipmentId = 1, shipmentNumber = "SHP-1", carrierName = "UPS", carrierService = "GROUND",
        requestedTracking = tracking, weight = BigDecimal("2.5"), clientId = 1,
    )

    @Test
    fun `manual adapter generates a MAN tracking when none supplied`() {
        val a = resolver.resolve(req(null))
        assertThat(a.trackingNumber).isEqualTo("MAN-SHP-1")
        assertThat(a.carrierName).isEqualTo("UPS")
    }

    @Test
    fun `manual adapter passes through a supplied tracking`() {
        assertThat(resolver.resolve(req("1Z999")).trackingNumber).isEqualTo("1Z999")
    }

    @Test
    fun `a higher-priority adapter wins over Manual for its carrier`() {
        val fedex = resolver.resolve(
            ManifestRequest(1, "SHP-9", "FEDEX", "EXPRESS", null, BigDecimal.ONE, 1),
        )
        assertThat(fedex.trackingNumber).isEqualTo("FX-SHP-9")
    }
}
