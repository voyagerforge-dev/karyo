package com.karyo.inventory.service

import com.karyo.inventory.api.spi.ReservationOutcome
import com.karyo.inventory.api.spi.ReservationRequest
import com.karyo.inventory.api.spi.ReservedStock
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.inventory.api.vo.StockSelectionRequest
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal

/**
 * Default in-process implementation of [StockReserver], scoped to the current tenant.
 *
 * Reuses the existing building blocks rather than reimplementing them:
 * [StockSelectionService] (13-pass FIFO selection) chooses the units, and
 * [StockService.reserveStock] / [StockService.releaseReservation] perform the
 * `reservedAmount` increment/decrement math (including journal + outbox records).
 */
@ApplicationScoped
class DefaultStockReserver(
    private val stockSelectionService: StockSelectionService,
    private val stockService: StockService,
    private val stockUnitRepository: StockUnitRepository,
    private val tenantContext: TenantContext,
) : StockReserver {

    @Transactional
    override fun reserve(request: ReservationRequest): ReservationOutcome = doReserve(request, tenantContext)

    @Transactional
    override fun reserve(request: ReservationRequest, clientId: Long): ReservationOutcome =
        doReserve(request, TenantContext.ownerScoped(clientId, tenantContext.username))

    /**
     * Shared selection + reserve loop behind both [reserve] overloads -- [tenant] is either the
     * injected ambient [TenantContext] or a [TenantContext.ownerScoped] one built from an explicit
     * `clientId`; the loop itself never reads ambient state directly.
     */
    private fun doReserve(request: ReservationRequest, tenant: TenantContext): ReservationOutcome {
        if (request.amount <= BigDecimal.ZERO) {
            return ReservationOutcome(emptyList(), BigDecimal.ZERO)
        }

        val selection = stockSelectionService.selectStock(
            StockSelectionRequest(
                itemDataId = request.itemDataId,
                amount = request.amount,
                clientId = tenant.clientId,
                lotNumber = request.lotNumber,
                useLockedStock = request.useLockedStock,
                preferComplete = request.preferComplete,
                preferMatching = request.preferMatching,
                completeHandling = request.completeHandling,
                enforceLot = request.enforceLot,
                excludeStockUnitIds = request.excludeStockUnitIds,
            )
        )

        val reservations = mutableListOf<ReservedStock>()
        var remaining = request.amount
        for (pick in selection.stocks) {
            if (remaining <= BigDecimal.ZERO) break
            val take = (pick.suggestedPickAmount ?: pick.availableAmount).min(remaining)
            if (take <= BigDecimal.ZERO) continue
            try {
                stockService.reserveStock(pick.stockUnitId, take, request.correlationId, tenant)
                reservations.add(ReservedStock(pick.stockUnitId, take))
                remaining = remaining.subtract(take)
            } catch (e: InventoryException) {
                // Selected unit became unreservable (locked / concurrently consumed) —
                // skip it and let the remainder surface as shortfall.
                LOG.warnf(
                    e,
                    "Skipping stock unit %d during reservation of item %d",
                    pick.stockUnitId,
                    request.itemDataId,
                )
            }
        }

        return ReservationOutcome(reservations, remaining.max(BigDecimal.ZERO))
    }

    @Transactional
    override fun release(reservations: List<ReservedStock>) = doRelease(reservations, tenantContext)

    @Transactional
    override fun release(reservations: List<ReservedStock>, clientId: Long) =
        doRelease(reservations, TenantContext.ownerScoped(clientId, tenantContext.username))

    /**
     * Shared release loop behind both [release] overloads -- same [tenant]-parameterization as
     * [doReserve]. A stock unit that does not resolve under [tenant] (gone, or -- for the explicit
     * overload -- genuinely owned by a different client, since [findByIdForWrite]-style scoping
     * reports a foreign unit as [InventoryException.NotFound], never a distinct "found but
     * forbidden") is skipped defensively, same as the pre-existing ambient behavior.
     */
    private fun doRelease(reservations: List<ReservedStock>, tenant: TenantContext) {
        for (reservation in reservations) {
            try {
                stockService.releaseReservation(
                    reservation.stockUnitId,
                    reservation.amount,
                    CORRELATION_ID,
                    tenant,
                )
            } catch (e: InventoryException.NotFound) {
                // Defensive release: unit gone (e.g. already deleted) — skip and log.
                LOG.warnf(
                    e,
                    "Stock unit %d no longer exists; skipping reservation release of %s",
                    reservation.stockUnitId,
                    reservation.amount,
                )
            }
        }
    }

    @Transactional
    override fun reserveOnStockUnit(stockUnitId: Long, amount: BigDecimal, correlationId: String): Boolean {
        if (amount <= BigDecimal.ZERO) return true
        return try {
            stockService.reserveStock(stockUnitId, amount, correlationId, tenantContext)
            true
        } catch (e: InventoryException) {
            // Same idiom as reserve()'s per-unit catch: locked / no-longer-enough-available is a
            // normal "can't reserve this one" outcome for a targeted reserve, not a module-boundary
            // exception -- the caller decides what a false means (e.g. skip this pick candidate).
            LOG.warnf(e, "Unable to reserve %s on stock unit %d", amount, stockUnitId)
            false
        }
    }

    @Transactional
    override fun reserveOnStockUnit(stockUnitId: Long, amount: BigDecimal, clientId: Long, correlationId: String): Boolean {
        if (amount <= BigDecimal.ZERO) return true
        val su = stockUnitRepository.findById(stockUnitId) ?: return false
        requireOwnedBy(su.clientId, clientId, stockUnitId)

        return try {
            stockService.reserveStock(stockUnitId, amount, correlationId, TenantContext.ownerScoped(clientId, tenantContext.username))
            true
        } catch (e: InventoryException) {
            // Same idiom as reserveOnStockUnit()'s ambient overload: locked / no-longer-enough-
            // available is a normal "can't reserve this one" outcome, not a module-boundary
            // exception. A clientId MISMATCH never reaches here -- requireOwnedBy already threw.
            LOG.warnf(e, "Unable to reserve %s on stock unit %d for client %d", amount, stockUnitId, clientId)
            false
        }
    }

    @Transactional
    override fun release(stockUnitId: Long, amount: BigDecimal, clientId: Long, correlationId: String) {
        if (amount <= BigDecimal.ZERO) return
        val su = stockUnitRepository.findById(stockUnitId)
        if (su == null) {
            // Defensive release: unit genuinely gone (e.g. already deleted) -- skip and log, same
            // idiom as release(List<ReservedStock>). NOT the same case as a clientId mismatch below.
            LOG.warnf(
                "Stock unit %d no longer exists; skipping reservation release of %s for client %d",
                stockUnitId,
                amount,
                clientId,
            )
            return
        }
        requireOwnedBy(su.clientId, clientId, stockUnitId)

        try {
            stockService.releaseReservation(stockUnitId, amount, correlationId, TenantContext.ownerScoped(clientId, tenantContext.username))
        } catch (e: InventoryException.NotFound) {
            // Extremely narrow race window (deleted between the findById above and this call) --
            // still a genuine "gone", not a scope mismatch (already ruled out by requireOwnedBy).
            LOG.warnf(
                e,
                "Stock unit %d no longer exists; skipping reservation release of %s for client %d",
                stockUnitId,
                amount,
                clientId,
            )
        }
    }

    /**
     * The distinguishing guard the explicit-`clientId` overloads exist for: a stock unit that
     * EXISTS but is owned by a different client throws [InventoryException.Forbidden] rather than
     * being folded into the ordinary "not found" / "can't reserve this one" outcomes, so a caller
     * bug (unprimed ambient context, wrong `clientId`) fails loudly instead of leaking as a
     * permanent `reservedAmount` drift.
     */
    private fun requireOwnedBy(actualClientId: Long, expectedClientId: Long, stockUnitId: Long) {
        if (actualClientId != expectedClientId) {
            throw InventoryException.Forbidden(
                "StockUnit $stockUnitId belongs to client $actualClientId, not $expectedClientId",
            )
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(DefaultStockReserver::class.java)
        private const val CORRELATION_ID = "stock-reserver"
    }
}
