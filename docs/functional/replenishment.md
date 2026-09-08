# Functional Specification: Replenishment

**Module:** Replenishment
**Status:** Documents implemented behavior as of 2026-08-30
**Audience:** Operators, implementation consultants, support engineers, and extension authors
**Source of truth:** `karyo-replenishment-{api,core}` with inventory, layout, and tasks SPIs

## 1. Purpose and outputs

Replenishment creates `REPLENISH` transport orders that move reserve stock to a deficient pick face
or storage area. `POST /api/v1/replenishment/scan` runs both modes for the caller's goods owner and
returns two lists:

- `generated`: one entry per transport order created in this pass.
- `shortfalls`: a deficiency that could not become an order, with `NO_SOURCE` or, for area mode,
  `NO_DESTINATION`.

`GET /api/v1/replenishment/needs` reports fix-face needs without creating work. Replenishment uses
the ordinary transport-order lifecycle and appears on the shared Tasks screen and floor work pool.

## 2. Source selection

The built-in `ReplenishmentSourceSelector` performs one read-only, FIFO-ordered search. Base
candidates are unlocked `ON_STOCK(300)` stock with positive available amount, ordered by
`strategyDate`, `amount`, `created`, then `id` ascending. It excludes the destination, every
additional location excluded by the caller, and unit loads already claimed by an open order or an
earlier deficiency in the same scan.

Fix-face source-area eligibility is controlled by the per-client runtime property
`karyo.replenishment.from-picking`, default `false`. When false, a PICKING-area unit load cannot be
a reserve source. When true, it can be considered, but a fix-assigned pick face never receives the
highest reserve-source tier merely because it is fix-assigned. Area mode always passes
`fromPicking = false`, so it never pulls a source from a PICKING area regardless of this property.

Strictness depends on the destination and candidate:

- For a picking-face destination, a source that is itself in a PICKING area may use any positive
  available amount. Other candidates must have no reservation and must be on a non-mixed unit load.
- For a storage-area destination, every source must have no reservation and must be on a non-mixed
  unit load, even when the from-picking property admits its area.

If the destination already carries lots, matching-lot candidates are preferred but not required.
Within the selected lot pool, FIFO stock on a fix-assigned reserve or bulk location wins first;
otherwise the first general FIFO candidate wins. The selector returns one whole unit load. It does
not reserve or mutate stock.

## 3. Fix-face replenishment

For each `FixAssignment`, the default `MIN_MAX` strategy triggers when current stock is below
`minAmount`. A failed current-amount read is unknown and skipped, never treated as zero. An open
replenishment order for the same assignment suppresses a duplicate.

When `maxAmount` is configured, Karyo requests a partial top-up equal to
`maxAmount - currentAmount`, capped at the source's available amount. A null or non-positive deficit,
or a request that consumes the whole source, uses the whole-unit-load path. Without `maxAmount`,
the whole source unit load moves.

## 4. Area-level replenishment

For every configured `ItemDataArea`, area mode compares eligible settled stock in the area's
cluster locations with two optional targets:

- amount deficiency: the eligible amount sum is below `plannedAmount`;
- stock-count deficiency: the eligible stock-row count is below `plannedStocks`.

A row contributes only when it belongs to the requested product and goods owner, is in
`ON_STOCK(300)`, has stock-level `lockType = 0`, has `reservedAmount = 0`, and sits in the area's
location set. The sum uses each eligible row's full `amount`; a partially reserved row is excluded
entirely rather than contributing its unreserved remainder. Unit-load lock state is not part of
this summary filter.

The area is deficient when either configured target is missed. An area with neither positive target
is skipped without a stock-summary query. An open area-replenishment order suppresses a duplicate.

A scan creates at most one order per deficient area. If one whole-unit-load move does not close the
deficit, the next scan re-evaluates the new on-hand state rather than looping within the same pass.
Area mode always requests a whole-unit-load move.

The source cannot come from inside the deficient area's own location set. The destination is the
lowest-id location in that area that is not a fix face, is unlocked, and has effective allocation
below 100. Effective allocation is the location's stored base allocation plus active
soft-reservation load, so a partially allocated location remains eligible while a full or fully
reserved one does not. Base allocation is a physical location property and is not recalculated
from stock rows or their owners during this scan. Lock, allocation, and name reads are scoped to the
scanning goods owner; a location owned by another client cannot resolve as the destination. This is
a deterministic v1 selector, not a capacity-ranked location search. If none qualifies, the scan
reports `NO_DESTINATION` instead of guessing. The selected location receives the same soft
reservation used for a known transport destination.

## 5. On-demand and scheduled scans

On-demand scanning is always available through the REST endpoint. Background scanning is a Karyo
operational addition and is opt-in:

- `KARYO_REPLENISHMENT_AUTO_SCAN`, default `false`;
- `KARYO_REPLENISHMENT_SCAN_INTERVAL`, default `10m`.

Each scheduled tick enumerates goods owners with fix assignments and isolates failures per owner.
The entire scheduled call graph passes `clientId` explicitly, so an unprimed request-scoped tenant
context cannot silently turn a scheduled read into client `0`.

## Extension points and code pointers

- `ReplenishmentStrategy`: decides whether a fix face is deficient. Built-in: `MIN_MAX`.
- `ReplenishmentSourceSelector`: chooses the reserve unit load using the rules above.
- `services/replenishment-service/karyo-replenishment-core/src/main/kotlin/com/karyo/replenishment/service/ReplenishmentService.kt`
- `services/replenishment-service/karyo-replenishment-core/src/main/kotlin/com/karyo/replenishment/service/AreaReplenishmentService.kt`
- `services/replenishment-service/karyo-replenishment-core/src/main/kotlin/com/karyo/replenishment/service/ReplenishmentScheduler.kt`
- `services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/spi/ReplenishmentSourceSelector.kt`
- `services/task-service/karyo-tasks-api/src/main/kotlin/com/karyo/tasks/spi/TransportOrderPort.kt`
