package com.karyo.fulfillment

import com.karyo.fulfillment.service.DocumentAvailabilityResolver
import com.karyo.fulfillment.spi.DocumentType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class DocumentAvailabilityResolverTest {

    @Inject lateinit var resolver: DocumentAvailabilityResolver

    @Test
    fun `default gates`() {
        assertThat(resolver.availableFrom(DocumentType.PACKING_SLIP)).isEqualTo(650)
        assertThat(resolver.availableFrom(DocumentType.BOL)).isEqualTo(670)
        assertThat(resolver.availableFrom(DocumentType.SHIPPING_LABEL)).isEqualTo(670)
        // D9: packet list shares the packing-slip gate (PACKED/650), not the BOL/label one —
        // a mutant that resolves it to 670 must fail this assertion.
        assertThat(resolver.availableFrom(DocumentType.PACKET_LIST)).isEqualTo(650)
    }
}
