package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * Picks the reserve unit-load to pull for a whole-UL replenishment.
 *
 * R14 (replenishment sprint Task 3) - lot-aware + fixed-reserve-first tiered source selection,
 * replacing the earlier crude "FIFO-oldest UL not on any fix-face" rule. Public behavioral
 * contract: `docs/functional/replenishment.md#2-source-selection`.
 *
 * 1. **Base candidates**: every unlocked ON_STOCK unit for the item, FIFO-ordered
 *    (`strategyDate ASC, amount ASC, created ASC, id ASC`) - unchanged from before.
 * 2. **Eligibility**: [SourceQuery.targetLocationId] is always excluded, as is every location in
 *    [SourceQuery.excludeLocationIds] (R12b, Task 6 — empty for every Mode-1/fix-face call, see
 *    that field's KDoc). A candidate sitting on a PICKING-usage location is excluded UNLESS
 *    [SourceQuery.fromPicking] (`karyo.replenishment.from-picking`, default `false`).
 * 3. **Strictness**: applied PER CANDIDATE, but which
 *    candidates get to use the loose rule depends on the DESTINATION face
 *    ([SourceQuery.targetIsPickingFace]) — **R15 (Task 4)**: a `targetIsPickingFace == false`
 *    (storage-face) query applies the strict rule (`reservedAmount == 0` AND a non-mixed
 *    unit-load, mirroring `ConfirmVariantService.singleLiveStockOrThrow`'s "exactly one live,
 *    non-DELETABLE stock row on the unit-load" definition) to EVERY candidate it considers,
 *    with the strict storage-source rule; a `targetIsPickingFace == true` (picking-face)
 *    query keeps the split - a candidate on a PICKING location (only reachable when
 *    [SourceQuery.fromPicking]) uses the looser `amount > reservedAmount` rule the base query
 *    already applies. **Karyo decision, still diverging from the predecessor behavior:** it never
 *    strict-screens a picking-face's OWN storage-area candidates either (single query, no
 *    per-candidate split) — Karyo keeps a storage candidate strict even under a picking-target
 *    scan, unifying the two legacy functions into one seam keyed by both the destination face AND
 *    each candidate's own location. This is a REQUIRED tightening, not merely a preference: this
 *    selector always returns a WHOLE unit-load (see the Scope-out note below) — a caller that
 *    moved a partially-reserved or mixed unit-load onto a fix face wholesale would drag someone
 *    else's reservation, or a foreign SKU, onto that face along with it.
 * 4. **Lot preference**: if [SourceQuery.faceLotNumbers] is non-empty,
 *    candidates whose `lotNumber` is in that set are preferred; if NONE match, every eligible
 *    candidate is still considered. **Karyo decision:** the predecessor applies lot matching as a HARD
 *    filter inside the JPQL (`stock.lotNumber in (:lots)`) — a face with an existing lot can ONLY
 *    ever be replenished from that same lot, full stop. Karyo softens this to a PREFERENCE
 *    (prefer-not-require): "don't mix lots onto a face" stays the common-case outcome, but a
 *    genuine shortage (the matching lot is exhausted everywhere) still gets *a* source rather
 *    than a false `NO_SOURCE`. This is NOT FEFO — no `bestBefore` ordering is introduced; the
 *    FIFO order from step 1 is preserved within each pool.
 * 5. **Tiering**: within the pool selected by step 4, Phase A =
 *    a candidate on a location that is itself fix-assigned ([SourceQuery.fixFaceLocationIds] —
 *    the scan's own full fix-face set, minus the target which step 2 already excludes) AND is
 *    NOT itself a picking-usage location — the Karyo reading of legacy's "fixed storage
 *    locations" phase, since Karyo's data model identifies a fix-assigned location via the
 *    `fix_assignments` table rather than a location flag. The picking-usage exclusion (Task 3
 *    review IMPORTANT-3) matters because `fixFaceLocationIds` is every fix assignment for the
 *    tenant, which includes OTHER pick faces, not just reserve/bulk slots — without it, once
 *    [fromPicking] admits picking-location candidates (R15/Task 4), a candidate on another
 *    fix-assigned PICK FACE would rank top-priority in Phase A, i.e. this selector would rob one
 *    pick face to feed another, which the predecessor's fixed-storage tier never allows.
 *    Phase B = every other eligible candidate. The FIFO-first of Phase A wins; if Phase A is
 *    empty, the FIFO-first of Phase B wins. Lot preference (step 4) OUTRANKS tiering: a
 *    lot-matching candidate in general storage beats a different-lot candidate on a fixed reserve
 *    slot — a **Karyo decision** keeping "don't mix a second lot onto a face" the strongest signal,
 *    stronger even than "prefer the dedicated reserve slot".
 *
 * Overridable via CDI `@Alternative` for custom slotting strategies.
 *
 * Scope-out (v1.3): partial/mixed UL selection is not supported — the returned unit-load is
 * assumed to be single-SKU and fully available for movement.
 *
 * R13 (still open): a selector that could return a PARTIAL amount off a source unit-load,
 * rather than the whole thing, now has a real confirm-time mechanism to land on —
 * `CompleteTransportOrderRequest.amount` / `ConfirmVariantService.completePartialIfApplicable`
 * (PT17, putaway-transport sprint Task 4) already support a REPLENISH transport order being
 * confirmed for less than its full [ReplenishmentSource.amount]. This selector itself is
 * unchanged by that — it still always returns a whole unit-load — R13 (a selector that reasons
 * about partial sourcing) remains a separate, not-yet-built SEAM.
 */
interface ReplenishmentSourceSelector {
    /** @return the best reserve source for [q], or null if none is available */
    fun selectSource(q: SourceQuery): ReplenishmentSource?
}

/** Identifies the unit-load chosen as a replenishment source. */
data class ReplenishmentSource(
    /** Id of the unit-load to move to the pick face. */
    val unitLoadId: Long,
    /** Gross `amount` on that unit-load (NOT `availableAmount`; reservations may exist on the unit). Informational — may exceed face capacity. */
    val amount: BigDecimal,
    /**
     * Row 5 (defect-burndown-4, Task 1): net of reservations (`amount - reservedAmount`).
     * [amount] stays gross and is used for the "does a partial cap consume the whole source"
     * whole-UL check ([com.karyo.replenishment.service.ReplenishmentService.topUpAmount]); a
     * genuine partial top-up must never request more than what is actually free to move,
     * regardless of how much of the source's gross amount is already reserved elsewhere.
     */
    val availableAmount: BigDecimal,
)

/**
 * R14 query for [ReplenishmentSourceSelector.selectSource] — see that interface's KDoc for the
 * full selection algorithm each field feeds.
 *
 * @param itemDataId the item to replenish
 * @param clientId tenant owner (the goods-owner client)
 * @param targetLocationId the fix-face location being replenished; always excluded as a candidate source
 * @param faceLotNumbers distinct non-blank lot numbers currently on ON_STOCK stock AT
 *   [targetLocationId] (e.g. via `StockUnitLookup.lotNumbersAtLocation`). Empty when the face
 *   carries no lot-tracked stock (or none at all) — no lot preference is applied in that case.
 * @param targetIsPickingFace whether [targetLocationId] itself sits in a PICKING-usage area (via
 *   [com.karyo.layout.spi.LocationAreaUsageLookup.pickingLocationIds]). **R15 (Task 4):** now
 *   consumed by `selectSource`'s strictness rule — see interface KDoc point 3.
 * @param fromPicking whether a candidate sitting on a PICKING-usage location may itself be used
 *   as a source (myWMS `Wms2Properties.KEY_REPLENISH_FROM_PICKING`, default false). **R15
 *   (Task 4):** wired from the SC16 runtime-property store (`karyo.replenishment.from-picking`)
 *   via `ReplenishmentService.scan`, resolved once per scan.
 * @param fixFaceLocationIds every fix-assigned location id for [clientId] (the scan's own
 *   candidate set) — used to tier eligible candidates into Phase A (a fix-assigned reserve/bulk
 *   slot) vs. Phase B (everything else).
 * @param excludeLocationIds R12b (replenishment sprint Task 6) — additional candidate locations
 *   to exclude beyond [targetLocationId]. Defaulted to `emptySet()` so every Mode-1 (fix-face)
 *   call site is unchanged. The area-level scan (Mode 2,
 *   [com.karyo.replenishment.service.ReplenishmentService.scanAreas]) is targeting a whole SET
 *   of locations (an [com.karyo.layout.spi.ItemDataAreaView]'s `clusterLocationIds`), not one
 *   fix face — [targetLocationId] alone can only ever exclude a single id, but moving stock
 *   from one location in the deficient area to another location in that SAME area would not fix
 *   the deficit, so the whole area must be excluded from candidacy. That caller passes
 *   `targetLocationId = -1` (an [com.karyo.layout.spi.ItemDataAreaView] has no single target
 *   face to name) and `excludeLocationIds = clusterLocationIds`.
 * @param excludeUnitLoadIds Row 3 (defect-burndown-4, Task 5) -- source unit-load ids already
 *   claimed by another deficiency earlier in the SAME scan pass (or by a still-open REPLENISH
 *   order from an earlier pass, via [com.karyo.tasks.spi.TransportOrderPort.
 *   openReplenishmentUnitLoadIds]). Without this, two deficient fix faces (or a fix face and an
 *   area) for the same item could both select the same FIFO-first source unit-load in one pass,
 *   each minting a transport order against stock the other order already committed to move.
 *   Defaulted to `emptySet()` so a caller that doesn't track claims (a test constructing a query
 *   directly) is unaffected.
 */
data class SourceQuery(
    val itemDataId: Long,
    val clientId: Long,
    val targetLocationId: Long,
    val faceLotNumbers: Set<String>,
    val targetIsPickingFace: Boolean,
    val fromPicking: Boolean,
    val fixFaceLocationIds: Set<Long>,
    val excludeLocationIds: Set<Long> = emptySet(),
    val excludeUnitLoadIds: Set<Long> = emptySet(),
)
