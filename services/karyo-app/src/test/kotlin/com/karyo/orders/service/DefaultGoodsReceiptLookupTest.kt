package com.karyo.orders.service

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.domain.model.GoodsReceiptLine
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.spi.GoodsReceiptLookup
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL beans) for [GoodsReceiptLookup.findByStockUnitIds] -- the batch
 * read seam backing supplier/ASN/received on [com.karyo.inventory.api.dto.StockUnitResponse].
 * Tenant-scoped exactly like [com.karyo.fulfillment.spi.ShipmentLookup.findByDeliveryOrderIds]:
 * unknown ids and foreign-tenant ids are simply absent from the result map (honest gap).
 */
@QuarkusTest
class DefaultGoodsReceiptLookupTest {

    @Inject
    lateinit var goodsReceiptLookup: GoodsReceiptLookup

    @Inject
    lateinit var goodsReceiptRepository: GoodsReceiptRepository

    @Inject
    lateinit var asnRepository: AsnRepository

    @Inject
    lateinit var tenantContext: TenantContext

    @Transactional
    fun persistReceiptFor(
        stockUnitId: Long,
        clientId: Long,
        supplierName: String? = "Acme Distribution",
        receiptDate: java.time.LocalDate? = null,
    ): GoodsReceipt {
        val seq = System.nanoTime()
        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = "ASN-LOOKUP-$seq"
            this.supplierName = supplierName
            this.state = OrderState.FINISHED.code
        }
        asn.lines.add(
            AsnLine().apply {
                this.asn = asn
                this.lineNumber = 1
                this.itemDataId = 42L
                this.itemDataNumber = "SKU-LOOKUP"
                this.expectedAmount = BigDecimal.TEN
                this.receivedAmount = BigDecimal.TEN
                this.state = OrderState.FINISHED.code
            },
        )
        asnRepository.persist(asn) // cascades AsnLine (ALL) -- line id available after this call

        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "GR-LOOKUP-$seq"
            this.state = OrderState.FINISHED.code
            this.receiptDate = receiptDate
        }
        receipt.lines.add(
            GoodsReceiptLine().apply {
                this.goodsReceipt = receipt
                this.asnLineId = asn.lines.first().id
                this.itemDataId = 42L
                this.itemDataNumber = "SKU-LOOKUP"
                this.amount = BigDecimal.TEN
                this.locationId = 1L
                this.locationName = "DOCK-01"
                this.unitLoadLabel = "UL-LOOKUP-$seq"
                this.stockUnitId = stockUnitId
                this.unitLoadId = 900L
            },
        )
        goodsReceiptRepository.persist(receipt)
        return receipt
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByStockUnitIds returns summary for own-tenant ids, omits unknown and foreign-tenant ids`() {
        val base = System.nanoTime()
        val ownStockUnitId = base
        val foreignStockUnitId = base + 1
        val unknownStockUnitId = base + 2

        val receipt = persistReceiptFor(ownStockUnitId, clientId = 1L, supplierName = "Northwind Traders")
        persistReceiptFor(foreignStockUnitId, clientId = 999L, supplierName = "Globex Supply Co")

        tenantContext.clientId = 1L
        val result = goodsReceiptLookup.findByStockUnitIds(
            setOf(ownStockUnitId, foreignStockUnitId, unknownStockUnitId),
        )

        assertThat(result).containsKey(ownStockUnitId)
        val summary = result[ownStockUnitId]!!
        assertThat(summary.receiptNumber).isEqualTo(receipt.receiptNumber)
        assertThat(summary.asnNumber).startsWith("ASN-LOOKUP-")
        assertThat(summary.supplierName).isEqualTo("Northwind Traders")
        assertThat(summary.receivedAt).isNotNull()

        assertThat(result).doesNotContainKey(foreignStockUnitId)
        assertThat(result).doesNotContainKey(unknownStockUnitId)
    }

    /**
     * B7: receivedAt PINS BOTH DIRECTIONS — an explicit operator-entered (backdated)
     * receiptDate wins (widened to Instant as start-of-day UTC, the summary's type);
     * absent receiptDate falls back to the record-creation timestamp, as before.
     */
    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receivedAt prefers the explicit receiptDate and falls back to created`() {
        val base = System.nanoTime()
        val datedStockUnitId = base
        val undatedStockUnitId = base + 1
        val backdated = java.time.LocalDate.of(2026, 7, 1)

        persistReceiptFor(datedStockUnitId, clientId = 1L, receiptDate = backdated)
        val undatedReceipt = persistReceiptFor(undatedStockUnitId, clientId = 1L)

        tenantContext.clientId = 1L
        val result = goodsReceiptLookup.findByStockUnitIds(setOf(datedStockUnitId, undatedStockUnitId))

        // Direction 1: explicit date wins — start-of-day UTC of the backdated LocalDate.
        assertThat(result[datedStockUnitId]!!.receivedAt)
            .isEqualTo(backdated.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
        // Direction 2: no receiptDate -> the receipt's created timestamp, unchanged behaviour.
        // (Millisecond tolerance — timestamptz ROUNDS the in-memory nanos to micros.)
        assertThat(result[undatedStockUnitId]!!.receivedAt)
            .isCloseTo(undatedReceipt.created, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findByStockUnitIds returns empty map for empty input`() {
        tenantContext.clientId = 1L
        assertThat(goodsReceiptLookup.findByStockUnitIds(emptySet())).isEmpty()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a stock unit never received via a GR is absent (honest gap, not fabricated)`() {
        tenantContext.clientId = 1L
        val neverReceivedId = System.nanoTime()
        assertThat(goodsReceiptLookup.findByStockUnitIds(setOf(neverReceivedId))).isEmpty()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a blind receipt (no ASN) carries a receipt and received but no supplier or ASN number`() {
        val stockUnitId = System.nanoTime()
        val seq = System.nanoTime()
        val receipt = GoodsReceipt().apply {
            this.clientId = 1L
            this.receiptNumber = "GR-BLIND-$seq"
            this.state = OrderState.FINISHED.code
        }
        receipt.lines.add(
            GoodsReceiptLine().apply {
                this.goodsReceipt = receipt
                this.itemDataId = 42L
                this.itemDataNumber = "SKU-BLIND"
                this.amount = BigDecimal.ONE
                this.locationId = 1L
                this.locationName = "DOCK-01"
                this.unitLoadLabel = "UL-BLIND-$seq"
                this.stockUnitId = stockUnitId
                this.unitLoadId = 901L
            },
        )
        persistBlind(receipt)

        tenantContext.clientId = 1L
        val result = goodsReceiptLookup.findByStockUnitIds(setOf(stockUnitId))
        val summary = result[stockUnitId]!!
        assertThat(summary.receiptNumber).isEqualTo(receipt.receiptNumber)
        assertThat(summary.asnNumber).isNull()
        assertThat(summary.supplierName).isNull()
        assertThat(summary.receivedAt).isNotNull()
    }

    @Transactional
    fun persistBlind(receipt: GoodsReceipt) {
        goodsReceiptRepository.persist(receipt)
    }
}
