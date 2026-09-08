package com.karyo.auth.config

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.exception.OrderException
import com.karyo.orders.service.OverReceiptGuard
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

/** The catalog BOOLEAN key wired into the first consumer (OverReceiptGuard). */
private const val OVER_RECEIPT_KEY = "karyo.receiving.allow-over-receipt"

/** The secret catalog key — its stored values are masked in the effective view. */
private const val SLACK_KEY = "karyo.alerts.slack.webhook-url"

/** Present ONLY in src/test/resources/application.properties — pins the CONFIG rung. */
private const val CONFIG_RUNG_KEY = "karyo.test.system-property-config-rung"

/**
 * SC16 resolution-ladder tests, direct-CDI. Service methods take an explicit clientId (no
 * TenantContext read inside the service — scoping is a REST concern), so no context priming
 * is needed here. The shared test DB rule: every test uses unique keys and/or throwaway
 * high client ids so no stored row can leak into another test's ladder (in particular, the
 * catalog over-receipt key is only ever written for throwaway client ids, never client 0).
 */
@QuarkusTest
class SystemPropertyServiceTest {

    @Inject
    lateinit var service: SystemPropertyService

    @Inject
    lateinit var lookup: RuntimePropertyLookup

    @Inject
    lateinit var overReceiptGuard: OverReceiptGuard

    private fun uniqueKey(prefix: String) = "karyo.test.$prefix.${System.nanoTime()}"

    // ── (a) exact-client row wins ─────────────────────────────────────────

    @Test
    fun `exact-client row wins over the client-0 row`() {
        val key = uniqueKey("ladder-a")
        service.set(0L, key, null, "system-value")
        service.set(7L, key, null, "client-7-value")

        assertThat(service.getString(key, 7L, "caller-default")).isEqualTo("client-7-value")
    }

    // ── (b) client-0 fallback ─────────────────────────────────────────────

    @Test
    fun `falls back to the client-0 row when the client has none`() {
        val key = uniqueKey("ladder-b")
        service.set(0L, key, null, "system-value")

        assertThat(service.getString(key, 8L, "caller-default")).isEqualTo("system-value")
    }

    // ── (c) MicroProfile config fallback ──────────────────────────────────

    @Test
    fun `falls back to MicroProfile config for the same key`() {
        assertThat(service.getString(CONFIG_RUNG_KEY, 9L, "caller-default")).isEqualTo("from-config")
    }

    @Test
    fun `a stored row beats the MicroProfile config value`() {
        val clientId = throwawayClientId()
        service.set(clientId, CONFIG_RUNG_KEY, null, "from-row")

        assertThat(service.getString(CONFIG_RUNG_KEY, clientId, null)).isEqualTo("from-row")
    }

    // ── (d) caller default ────────────────────────────────────────────────

    @Test
    fun `falls back to the caller default when nothing is stored or configured`() {
        val key = uniqueKey("ladder-d")
        assertThat(service.getString(key, 10L, "caller-default")).isEqualTo("caller-default")
        assertThat(service.getString(key, 10L, null)).isNull()
    }

    // ── (e) typed getters ─────────────────────────────────────────────────

    @Test
    fun `getBoolean parses true and false case-insensitively`() {
        val key = uniqueKey("ladder-e-bool")
        service.set(11L, key, null, "TRUE")
        assertThat(service.getBoolean(key, 11L, false)).isTrue()

        service.set(11L, key, null, "False")
        assertThat(service.getBoolean(key, 11L, true)).isFalse()
    }

    @Test
    fun `getBoolean returns the default on garbage`() {
        val key = uniqueKey("ladder-e-garbage")
        service.set(12L, key, null, "not-a-bool")

        assertThat(service.getBoolean(key, 12L, true)).isTrue()
        assertThat(service.getBoolean(key, 12L, false)).isFalse()
    }

    @Test
    fun `getInt parses a stored number and defaults on garbage`() {
        val key = uniqueKey("ladder-e-int")
        service.set(13L, key, null, "42")
        assertThat(service.getInt(key, 13L, 7)).isEqualTo(42)

        service.set(13L, key, null, "forty-two")
        assertThat(service.getInt(key, 13L, 7)).isEqualTo(7)
    }

    // ── (f) catalog type validation ───────────────────────────────────────

    @Test
    fun `setting a non-boolean value on a BOOLEAN catalog key throws`() {
        assertThatThrownBy { service.set(throwawayClientId(), OVER_RECEIPT_KEY, null, "abc") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a non-catalog key accepts any value`() {
        val key = uniqueKey("free-form")
        assertThatCode { service.set(14L, key, null, "anything at all") }.doesNotThrowAnyException()
    }

    // ── upsert respects the existing row ──────────────────────────────────

    @Test
    fun `set upserts - second write updates the same row`() {
        val key = uniqueKey("upsert")
        service.set(15L, key, null, "v1")
        service.set(15L, key, null, "v2")

        assertThat(service.getString(key, 15L, null)).isEqualTo("v2")
        assertThat(service.effectiveView(15L).count { it.key == key }).isEqualTo(1)
    }

    @Test
    fun `delete removes the stored row and reports absence`() {
        val key = uniqueKey("delete")
        service.set(16L, key, null, "v1")

        assertThat(service.delete(16L, key, null)).isTrue()
        assertThat(service.getString(key, 16L, null)).isNull()
        assertThat(service.delete(16L, key, null)).isFalse()
    }

    // ── secret masking is view-only ───────────────────────────────────────

    @Test
    fun `effective view masks a secret catalog key but the ladder stays raw for consumers`() {
        val clientId = throwawayClientId()
        val raw = "https://hooks.example/raw-$clientId"
        service.set(clientId, SLACK_KEY, null, raw)

        val view = service.effectiveView(clientId).first { it.key == SLACK_KEY }
        assertThat(view.value).isEqualTo(SystemPropertyService.SECRET_MASK)
        assertThat(view.secret).isTrue()

        // In-process consumers (typed getters / RuntimePropertyLookup) still get the real value.
        assertThat(service.getString(SLACK_KEY, clientId, null)).isEqualTo(raw)
        assertThat(lookup.getString(SLACK_KEY, clientId, null)).isEqualTo(raw)
    }

    // ── SPI delegates to the service ──────────────────────────────────────

    @Test
    fun `RuntimePropertyLookup resolves through the same ladder`() {
        val key = uniqueKey("spi")
        service.set(0L, key, null, "17")

        assertThat(lookup.getInt(key, 17L, 0)).isEqualTo(17)
        assertThat(lookup.getString(uniqueKey("spi-miss"), 17L, "dflt")).isEqualTo("dflt")
    }

    // ── Step 8: first consumer — the over-receipt guard reads the store ───

    @Test
    fun `client-scoped false row hard-stops over-receipt even though the env default is true`() {
        val stoppedClient = throwawayClientId()
        val otherClient = throwawayClientId()
        service.set(stoppedClient, OVER_RECEIPT_KEY, null, "false")

        // Over-receipt (8 + 5 > 10) with the per-request override set: the DB row for this
        // client is the STRICTER gate and wins over the env/config default (true).
        assertThatThrownBy { overReceiptGuard.check(asnLine(stoppedClient), overReceipt()) }
            .isInstanceOf(OrderException.OverReceipt::class.java)

        // Any other client is unaffected — the fallback (config default true) still lets the
        // per-request override through.
        assertThatCode { overReceiptGuard.check(asnLine(otherClient), overReceipt()) }
            .doesNotThrowAnyException()
    }

    private fun asnLine(clientId: Long): AsnLine {
        val line = AsnLine()
        line.id = 1L
        line.expectedAmount = BigDecimal("10")
        line.receivedAmount = BigDecimal("8")
        line.asn = Asn().apply { this.clientId = clientId }
        return line
    }

    private fun overReceipt() = ReceiveLineRequest(
        asnLineId = 1L,
        amount = BigDecimal("5"),
        locationId = 900L,
        locationName = "DOCK-01",
        allowOverReceipt = true,
    )

    companion object {
        /** Unique high client ids so catalog-key rows never touch a real/seeded client. */
        private val CLIENT_SEQ = AtomicLong(System.nanoTime() % 1_000_000 + 5_000_000)
        private fun throwawayClientId(): Long = CLIENT_SEQ.incrementAndGet()
    }
}
