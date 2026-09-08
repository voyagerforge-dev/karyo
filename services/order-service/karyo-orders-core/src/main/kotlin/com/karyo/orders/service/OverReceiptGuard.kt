package com.karyo.orders.service

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.orders.config.ReceivingConfig
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.exception.OrderException
import jakarta.enterprise.context.ApplicationScoped

/** The SC16 catalog key the instance-level over-receipt knob lives under. */
private const val ALLOW_OVER_RECEIPT_KEY = "karyo.receiving.allow-over-receipt"

/**
 * Over-receipt guard: 409 when received + amount exceeds expected, unless BOTH the
 * per-request `allowOverReceipt` AND the instance-level knob allow it. The instance knob
 * is the STRICTER gate -- `false` hard-stops regardless of what any individual request
 * asks for; `true` (the default) reproduces the original per-request-only behavior exactly.
 *
 * SC16 (first runtime-store consumer): the instance knob is now resolved DB-first through
 * [RuntimePropertyLookup] for the receiving client -- a stored
 * `karyo.receiving.allow-over-receipt` row (client-scoped, or the client-0 instance row)
 * wins, and the env-driven [ReceivingConfig.allowOverReceipt] value is demoted to the
 * fallback default at the bottom of the ladder. The lookup only fires on an actual
 * over-receipt, so the normal receive path gains no DB read.
 *
 * Extracted as its own bean (rather than a private method on [GoodsReceiptService])
 * because [GoodsReceiptService] is AT the detekt `LongParameterList` constructor-param
 * limit (10) and cannot take these as extra dependencies. Injected into
 * [AsnService] instead -- already one of [GoodsReceiptService]'s existing 10
 * dependencies with constructor headroom, and already the owner of the AsnLine amount
 * bookkeeping ([AsnService.recordReceipt]/[AsnService.retractReceipt]) this guard
 * protects (see [AsnService.checkOverReceipt]). [GoodsReceiptService]'s own constructor
 * is untouched.
 */
@ApplicationScoped
class OverReceiptGuard(
    private val config: ReceivingConfig,
    private val runtimeProperties: RuntimePropertyLookup,
) {

    fun check(asnLine: AsnLine, request: ReceiveLineRequest) {
        val newTotal = asnLine.receivedAmount.add(request.amount)
        if (newTotal <= asnLine.expectedAmount) return
        val instanceAllows = runtimeProperties.getBoolean(
            ALLOW_OVER_RECEIPT_KEY, asnLine.asn.clientId, config.allowOverReceipt,
        )
        if (!(request.allowOverReceipt && instanceAllows)) {
            throw OrderException.OverReceipt(
                asnLine.id!!, asnLine.expectedAmount, asnLine.receivedAmount, request.amount,
            )
        }
    }
}
