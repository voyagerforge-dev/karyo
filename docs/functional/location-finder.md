# Functional Specification: Putaway Location Finder

**Module:** Warehouse Layout · **Status:** Documents implemented behavior as of 2026-08-16.
**Audience:** Implementation consultants, support engineers, enterprise evaluators, AI/RAG knowledge base
**Honesty rule:** This spec describes what the **code does today**. Where Karyo implements less than myWMS (its functional ancestor, a 19-filter algorithm), the gap is stated explicitly — this document doubles as the gap tracker for the putaway finder.

---

## 1. Purpose & Trigger

The location finder answers one question: *given a unit load that needs to be put away, which storage location should it go to?*

It is the layout module's placement brain. Karyo independently implements **14 built-in filter passes** and records the exact supported behavior and remaining divergence in this specification. The layout module **owns** the search because it is the only module that knows areas, zones, location types, locking, and allocation.

**Two search modes since 2026-08-16 (LF10):** `findPutawayLocation` (below, §2-§4) answers "where should this incoming unit load go" over empty/under-full locations; a second, wholly separate `findAddToLocation` mode (§2a) answers "can this stock consolidate onto an existing pick face" - it searches stock, not locations, and is purely advisory (no soft reservation). Both live on the same `LocationFinder` SPI.

**When it runs:**

- **Auto-putaway (primary):** when a goods-receipt line is received, the tasks module observes `GoodsReceiptLineReceivedEvent` and calls `LocationFinder.findPutawayLocation(...)` in-process to seed a PUTAWAY transport order's suggested destination. It is re-run on task *start* if the suggestion is missing/stale.
- **In-process only:** callers use the `LocationFinder` CDI bean directly. (The old `POST /api/internal/locations/find-putaway` route was removed with the `/api/internal` surface; stale pointer corrected 2026-08-16.)

**Unlike stock selection, the finder is NOT advisory/read-only — it soft-reserves.** A successful result writes a short-lived `LocationReservation` row (see §4) so two concurrent putaways are never handed the same nearly full location.

**Inputs** (`LocationFinderRequest`):

| Input | Required | Meaning |
|---|---|---|
| `unitLoadId` | yes | The unit load being put away (audit/trace) |
| `unitLoadTypeId` | no | UL type — feeds filter 7 (UL-type compatibility) |
| `weight` | yes (ZERO ok) | UL weight for the lifting-capacity filter (filter 5); ZERO = "fits anywhere" |
| `clientId` | no | Goods owner (silo meaning) — filters 6 & 8; null = shared/unscoped |
| `reservationKey` | yes | Caller's correlation id for the soft reservation — the tasks module passes the **transport order id** so it can later release by the same key |
| `storageStrategyId` | no | Layout strategy driving zone preference + client mixing |
| `preferredZoneId` | no | Explicit zone constraint (overrides the strategy zone) |
| `pickingOnly` | no (default false) | Target PICKING areas instead of STORAGE (replenishment-style); v1.2 putaway always false |

**Output** (`LocationFinderResult`, a sealed type):

- `Found(locationId, locationName)` — a location was chosen **and soft-reserved**.
- `NoLocation(reason)` — nothing qualified; `reason` names the constraint that emptied the candidate set (useful for the operator and, later, the copilot explaining *why*). **NoLocation is a valid, expected result** — the caller (tasks module) still creates the putaway task so the work is never lost, just without a suggestion.

---

## 2. The Built-In Filter Passes — As Implemented

Filters run in this decision order. Filters 1-6, 11, and 12 are cheap **SQL predicates** (indexed via `idx_locations_finder (zone_id, area_id, lock_type, allocation)`); filters 3-tail, 7-10, and 13-14 are **in-service post-filters** over the small surviving set. The candidate query eager-joins `locationType`/`area`/`zone`/`locationCluster` so the post-filters need no extra round trips. (Passes 9-11 and the real filter 7 shipped 2026-07-25 with the locations-layout sprint; passes 12-14 shipped 2026-08-16 with the location-finder sprint, LF8/LF9; this section was stale until 2026-08-16.)

| # | Filter | Where | What it excludes |
|---|---|---|---|
| 1 | **Area usage** | SQL (`area.usages LIKE '%STORAGE%'`, or `'%PICKING%'` when `pickingOnly`) | Locations whose area is not designated for storage (or picking) |
| 2 | **Unlocked** | SQL (`lockType = 0`) | Any locked location (any non-zero lock) |
| 3 | **Effective allocation < 100** | SQL base (`allocation < 100`) + in-service reservation fold | Full locations; *and* locations whose base allocation plus active soft reservations reaches 100 (see §4) |
| 4 | **Zone match** | SQL (`zone.id = :zoneId`) when a zone is resolved | Locations outside the resolved zone. Zone resolution: `preferredZoneId` wins, else the strategy's zone, else no zone constraint |
| 5 | **Lifting capacity ≥ weight** | SQL (`liftingCapacity IS NULL OR = 0 OR ≥ :weight`) | Locations whose type cannot bear the incoming UL weight *on its own*; null/zero capacity = unlimited. **Known gap:** the predicate does not count the weight already resting on the candidate, so a load can still be routed onto an already-loaded rack (see §8) |
| 6 | **Client ownership** | SQL (`clientId = 0 OR clientId = :clientId`) | Locations dedicated to a *different* goods owner; shared (clientId 0) and own-client locations pass |
| 7 | **Unit-load-type compatibility + capacity** | in-service, batched | Shipped 2026-07-25 (L2/T4): the `TypeCapacityConstraint` (LocationType, UnitLoadType) matrix. No rows at all = unrestricted; rows-but-no-match = excluded; a matching row gates on effective allocation; oversize (`allocation > 100`) rows exclude any non-empty candidate (myWMS's multi-position rule NOT built, see §9) |
| 8 | **Client mixing** | in-service (layout-local, cheap first gate) | When `strategy.mixClient == false`: locations dedicated to a different client (by the location's own `clientId`). Real occupancy-by-client mixing (stock + in-flight transports) shipped 2026-08-16 as pass 13 below (see §9, WORKLIST LF9) |
| 9 | **StorageArea restriction + hiding** | in-service, batched | Shipped 2026-07-25 (L1/T3): with `StorageStrategyArea`s configured, candidates restrict to the areas' clusters (no areas configured = no restriction, a documented deviation); `useAreaStrategyDate` (cross-area FIFO) and `useItemDataArea` (full-area) hide whole areas |
| 10 | **Field/section group lifting capacity** | in-service, batched | Shipped 2026-07-25 (L4/T7): excludes candidates whose (area, rack, field) or (area, section) group weight plus the incoming UL would exceed the `LocationType` group caps |
| 11 | **Allocation-state** | SQL (`allocation_state = 0`) | Shipped 2026-07-25 (L3/T6): operator "mark full/blocked" flag excludes the location |
| 12 | **Fixed-assignment exclusion** | SQL (`NOT EXISTS (... FROM fix_assignments fix WHERE fix.storage_location_id = location.id)`) | Shipped 2026-08-16 (LF8): a location carrying ANY `FixAssignment` (any item, any client) leaves the general-putaway pool unconditionally - no knob. Release note: an existing STORAGE-area fix assignment starts being excluded on this release (blast radius small - fix assignments in practice sit on PICKING-area locations general STORAGE putaway never targeted) |
| 13 | **Client mixing - occupancy + in-flight transport** | in-service, batched (`OccupancyMixReader`) | Shipped 2026-08-16 (LF9): the second, real gate after filter 8's cheap attribute check. When `strategy.mixClient == false`, it excludes a candidate whose live occupant stock (any state below `DELETABLE`, amount > 0) or open in-flight transport demand belongs to a different client than the request's. One batched `StockUnitLookup.occupantsByLocationIds` call (deliberately unscoped) and one batched `TransportDemandLookup.openDemandByLocationIds` call per find are shared with pass 14; both calls are skipped when client and item mixing are allowed. Open demand includes every non-FINISHED, non-CANCELED transport whose `destinationLocationId` targets the candidate, or whose destination is still null and `suggestedLocationId` targets it. PUTAWAY and TRANSFER suggestions therefore participate alongside MOVE and REPLENISH destinations. Demand from the requesting transport order itself is ignored. |
| 14 | **Item mixing (`mixItem`)** | in-service, batched (`OccupancyMixReader`) | Shipped 2026-08-16 (LF8): when `strategy.mixItem == false` (default `true`, so inert until a strategy opts in), excludes a candidate whose live occupant stock or open in-flight transport demand carries a different `itemDataId` than the request's |

Ordering is likewise no longer fixed: `strategy.sorts` (9 typed sort keys) and the `nearPickingLocation` X-distance prepend shipped 2026-07-25 (T5); a same-rack PREFERENCE comparator (LF8, prepended ahead of the X-distance sort) shipped 2026-08-16; see §3.

**Filter 7 detail (UL-type compatibility).** Shipped 2026-07-25 (layout V313, `type_capacity_constraints`): loaded in ONE batched query per find call (never per-candidate) via `TypeCapacityConstraintRepository.findByLocationTypes`. (The earlier text here claiming "that table does not exist" was stale from v1.2; corrected 2026-08-16.)

**Filter 8 detail (client mixing) - v1.2 simplification, upgraded 2026-08-16 (LF9).** myWMS excludes a location if *any unit load currently stored there* (or any in-flight transport targeting it) belongs to a different client - a cross-module occupancy query. Karyo v1.2 reasoned only from the location's own `clientId` attribute: a non-shared location owned by a different client is excluded; shared (0) and same-client locations were allowed, with occupancy-by-client mixing deferred. That deferral is now closed: filter 8's attribute check stays as the cheap first gate, and pass 13 (above) adds the real occupancy + in-flight-transport check as a second gate, via one deliberately-unscoped `StockUnitLookup.occupantsByLocationIds` and one `TransportDemandLookup` SPI (`karyo-layout-api`, implemented in `karyo-tasks-core`, the `OpenPickGuard` direction - zero new Gradle edges, since `karyo-tasks-core` already depends on `karyo-layout-api`). The transport lookup resolves an open order's target from `destinationLocationId` first and otherwise `suggestedLocationId`, so all transport types use whichever target is currently known. The finder is no longer purely layout-local for this check, but the cross-module reads stay narrowly scoped to the one boolean question each answers.

---

### 2a. The Add-To-Location Search Mode (LF10)

`findAddToLocation(request: AddToLocationRequest): AddToLocationResult` is a **second, wholly separate search mode** on the `LocationFinder` SPI, shipped 2026-08-16. This section is its public behavioral contract. It answers a different question than `findPutawayLocation`: *given stock that already exists (by item/lot/best-before), is there an existing pick-face unit load it can consolidate onto?* It searches **stock**, not empty locations, and shares none of the putaway filters (no allocation, capacity, zone, or weight logic).

- **Not a `LocationFilter`.** The mode doesn't consume putaway candidates at all, so routing it through the filter chain would be shape-faking.
- **Advisory - no `LocationReservation` write.** `Found`'s contract for putaway is "found AND soft-reserved"; consolidating onto occupied stock cannot be double-booked the way an emptying slot can (two concurrent add-tos onto the same pick face are physically fine), so this mode returns its own sealed `AddToLocationResult` instead of corrupting the reserved-implying `LocationFinderResult` contract. Callers that need exclusivity reserve explicitly via `reserve()`.
- **`manualSearch` bypass.** When the resolved strategy has `manualSearch == true`, the mode short-circuits to `None("... requires manual placement (manualSearch)")` before any query, exactly as putaway does.
- **Two priorities, first-fit:**
  1. **Fixed picking location.** The item's `FixAssignment`s, in `orderIndex` (then id) order, unlocked, PICKING-area only. A candidate location qualifies when all same-item stock already sitting there shares the request's lot with **null-matches-null** semantics (both null = match), and carries no vetoed stock unit id.
  2. **FIFO consolidation.** Falls back to the FIFO-ordered pickable stock for the item (`ON_STOCK`, unlocked, lot/best-before equality only when specified in the request, order `strategyDate ASC, amount ASC, created ASC, id ASC`, capped at 200 refs - the same pragmatic cap the putaway candidate query already uses), filtered layout-side to unlocked PICKING locations, taking the first non-vetoed survivor.
- **Karyo-shape divergence.** Legacy's signature takes a source `StockUnit` and guards `state != ON_STOCK -> null` internally. Karyo's `AddToLocationRequest` takes the attributes instead (`clientId`, `itemDataId`, `lotNumber`, `bestBefore`, `vetoStockUnitIds`, `storageStrategyId`) because the finder must not fetch a foreign module's entity to unpack it - the `ON_STOCK`-source guard is the caller's own business.
- **No REST surface, no caller rewiring.** Putaway itself has no REST route; this mode matches it. Wiring replenishment or receiving to *call* the new mode is out of scope by ruling - the row delivers the capability at the seam, not a new consumer.
- **Data plumbing.** Two new tenant-scoped, explicit-`clientId` `StockUnitLookup` methods: `itemStocksByLocationIds` (priority 1) and `fifoConsolidationRefs` (priority 2), backed by a new `AddToLocationFinder` collaborator bean (kept separate from `LocationFinderService`, which was already near the detekt 25-function ceiling and an 11-parameter constructor before this sprint).

---

## 3. Ordering & Tie-Break

Survivors are ordered by:

1. **Effective allocation ASC**, emptiest first (spread load): the *default* when the strategy sets no `sorts`; since 2026-07-25 (T5) `strategy.sorts` can override with a typed comparator chain (9 sort types incl. `ALLOCATION DESC` consolidation), `useAreaStrategyDate` prepends the strategy's area order, and `nearPickingLocation` prepends an X-distance-to-fix-assignment preference.
2. **Location name ASC** — a stable, deterministic tie-break, so two identical requests always pick the same location.

The candidate SQL orders by *base* allocation; because soft reservations can only *raise* effective allocation, the service re-sorts the small surviving set by effective value before handing it to the SPI filters.

**Critical — SPI filter order is HONORED.** After the built-in ordering, every `LocationFilter` SPI runs in priority order and may prune **and/or reorder** candidates. The finder takes `first()` of whatever the last filter returns and **does NOT re-sort**. This is the explicit lesson from the stock-selection FSD finding (where the inventory selector silently re-imposed FIFO and discarded SPI reordering — divergence #9 in [stock-selection.md](stock-selection.md)). A test (`LocationFinderTest`) registers a reversing filter and asserts the finder returns the filter's first element, proving the order is respected.

---

## 4. Reservation Semantics

To stop two concurrent putaways from being handed the same near-full location, a `Found` result writes a `LocationReservation` row:

- **Key:** `reservationKey` (the caller's transport order id).
- **Load:** `percent = 100` (one UL fully reserves a location for v1.2, mirroring the fixed 100%-per-UL allocation model).
- **TTL:** 10 minutes (`expiresAt = now + 10m`).

The filter-3 emptiness gate counts **active** (non-expired) reservations: `effectiveAllocation = location.allocation + Σ(active reservation percent)`. A location at base allocation 0 with one active reservation is therefore treated as 100 → full → excluded from the next call.

**Lifecycle:**

- **Created** by the finder on `Found`, inside the finder's transaction.
- **Released** explicitly via `LocationFinder.releaseReservation(transportOrderId)` — the tasks module calls this on putaway **completion** (the real allocation now reflects the move) and on **cancel**.
- **Swept** by a `@Scheduled(every = "60s")` job that deletes anything past `expiresAt`, so a crashed/abandoned putaway never permanently blocks a location.

Release is idempotent (a no-op when no reservation exists).

---

## 5. SPI Seams (Extension Points)

- **`LocationFilter`** (in `karyo-layout-api`) — post-filter / reorder candidates. Every discovered bean runs in ascending `priority()` order; each receives the candidate list + the request and returns the list to carry forward (a subset and/or a reordering). **The finder honors the returned order (no re-sort)** — see §3. Returning an empty list vetoes all candidates (→ NoLocation). Deploy in a client extension JAR compiled against the `api` module only.
- **`PutawayLocationStrategy`** (in `karyo-layout-api`) — **fully replace** the finder. All discovered strategies are tried in ascending `priority()`; the **first non-null** result wins. The built-in finder registers at the lowest priority (`Int.MAX_VALUE`) so it always runs last; a custom strategy at any lower priority pre-empts it. A custom strategy owns its own reservation semantics; the built-in writes the standard `LocationReservation`.

The built-in `LocationFinderService` implements **both** the public `LocationFinder` facade and the built-in `PutawayLocationStrategy` (its strategy method is named `tryFindPutawayLocation` so one class can carry both contracts).

---

## 6. Worked Examples

All examples: client 1, putaway (not pickingOnly), each location's type lifting capacity 1000kg unless stated, all locations in the test's zone Z.

### Example A — emptiest STORAGE location wins; locked/full excluded

| Location | Area usage | Lock | Base allocation |
|---|---|---|---|
| L-EMPTY | STORAGE | 0 | 0 |
| L-HALF | STORAGE | 0 | 50 |
| L-LOCKED | STORAGE | 1 | 0 |

Filter 1 keeps all three (all STORAGE). Filter 2 drops **L-LOCKED**. Filter 3 keeps L-EMPTY and L-HALF (both < 100). Ordering: allocation ASC → **L-EMPTY (0) wins** over L-HALF (50). **Result: `Found(L-EMPTY)`**, and L-EMPTY is now soft-reserved.

### Example B — reservation steers the next putaway elsewhere

Two empty STORAGE locations L-A, L-B in zone Z. Call 1 (`reservationKey=100`) → `Found` one of them (say L-A) and reserves it at 100%. Call 2 (`reservationKey=101`), same zone: L-A's effective allocation is now 0 + 100 = 100 → **excluded by filter 3**; only L-B survives. **Result: `Found(L-B)`.** Releasing reservation 100 frees L-A again. (This is exactly `LocationFinderTest`'s reservation case.)

### Example C — weight excludes under-capacity locations

| Location | Type lifting capacity | Base allocation |
|---|---|---|
| L-LIGHT | 100 | 0 |
| L-HEAVY | 5000 | 0 |

Request `weight = 500`. Filter 5 drops **L-LIGHT** (100 < 500); L-HEAVY passes. **Result: `Found(L-HEAVY)`** even though both were empty — capacity outranks emptiness because it is a hard predicate, not a sort key.

### Example D — NoLocation with a reason

Zone Z contains only a **PICKING** location (no STORAGE). A default putaway (STORAGE) scoped to zone Z: filter 1 finds no STORAGE candidate. **Result: `NoLocation("no unlocked STORAGE location with free capacity in zone …")`.** The tasks module still creates the putaway task (CREATED, no suggestion, note = the reason) so an operator can place it manually.

---

## 7. Code Pointers

| What | Where |
|---|---|
| Finder algorithm (14 passes, reservation, SPI chain, ordering) | `services/warehouse-layout-service/karyo-layout-core/.../service/LocationFinderService.kt`: `findPutawayLocation()` (facade), `tryFindPutawayLocation()` (built-in strategy), `findAddToLocation()` (LF10), `clientMixingAllowed()`, `capacityVerdict()`, `sweepExpiredReservations()`; helpers `AreaOccupancyReader`, `GroupCapacityReader`, `CandidateOrdering`, `OccupancyMixReader` (LF8 mixItem + LF9 mixClient, shared batched reads), `AddToLocationFinder` (LF10 collaborator) |
| Candidate query + FIFO-of-locations ordering | `.../repository/StorageLocationRepository.kt` — `findPutawayCandidates()` (LF8 fixed-assignment `NOT EXISTS` predicate) |
| Reservation entity + repo | `.../domain/model/LocationReservation.kt`, `.../repository/LocationReservationRepository.kt`; migration `services/karyo-app/.../db/migration/layout/V308__create_location_reservations.sql` |
| Contracts + SPI seams | `karyo-layout-api/.../spi/LocationFinder.kt` (now also `findAddToLocation`), `LocationFilter.kt`, `PutawayLocationStrategy.kt`, `LocationCandidate.kt`, `TransportDemandLookup.kt` (new, LF9) |
| Cross-module lookups (LF9/LF10) | `karyo-inventory-api/.../spi/StockUnitLookup.kt` - `occupantsByLocationIds` (LF9, deliberately unscoped), `itemStocksByLocationIds`/`fifoConsolidationRefs` (LF10, tenant-scoped); impl `DefaultStockUnitLookup.kt`; `karyo-tasks-core/.../service/DefaultTransportDemandLookup.kt` (LF9, the `OpenPickGuard` direction) |
| Tasks-module consumer (auto-putaway) | `services/task-service/karyo-tasks-core/.../service/TaskService.kt` — `onGoodsReceiptLineReceived()`, `findLocation()` |
| Tests | `services/karyo-app/src/test/kotlin/com/karyo/layout/service/LocationFinderTest.kt`, `LocationFinderFixExclusionTest.kt` (LF8), `LocationFinderMixTest.kt` (LF8/LF9), `LocationFinderSortTest.kt` (LF8 rack preference), `AddToLocationTest.kt` (LF10); `.../com/karyo/inventory/service/StockUnitLookupOccupantsTest.kt` (LF9), `StockUnitLookupConsolidationTest.kt` (LF10); `.../com/karyo/tasks/service/TransportDemandLookupTest.kt` (LF9); closing loop `.../com/karyo/app/PutawayFlowTest.kt` |
| Behavioral contract | This specification, especially §2-§4; implementation in `LocationFinderService` and its collaborators |

---

## 8. Configuration Surface — implemented vs. myWMS

| Lever | myWMS | Karyo (corrected 2026-08-16) |
|---|---|---|
| Area usage (STORAGE/PICKING) | Filter 6 | **Implemented** (filter 1) |
| Location lock | Filter 7 | **Implemented** (filter 2) |
| Allocation < 100 | Filter 5 | **Implemented** (filter 3) + soft-reservation fold (Karyo addition, replaces myWMS filter 18 `locationReserver`) |
| Zone | Filter 11 (zone flow) | **Implemented** as a single preferred/strategy zone (filter 4); full zone-flow/overflow resolution not implemented |
| Weight / lifting capacity | Filters 10 + 17 (3-level: location/field/section) | **PARTIAL (corrected 2026-09-06)**: all three levels exist, but only the field/section group rollups (pass 10, shipped 2026-07-25 L4/T7) add a candidate's already-occupied weight to the incoming load. The location-type predicate (filter 5) compares the cap against the incoming weight alone, so a location-level cap never stops an already-loaded rack from taking more, and the rollups engage only where the type also defines `fieldLiftingCapacity`/`sectionLiftingCapacity` - a plainly-capped type gets no cumulative check at all. (`LocationService.checkCapacity` does count occupied weight since 2026-09-06, but it is an in-process seam the finder does not call.) |
| Client ownership | Filter 8 (`strategy.onlyClientLocation`) | **Implemented** (filter 6); `onlyClientLocation` is a real per-strategy flag since 2026-07-25 (L6), default TRUE since defect-burndown-4 row 17 |
| UL-type compatibility | Filter 3 (`TypeCapacityConstraint`) | **Implemented** (filter 7, shipped 2026-07-25 L2/T4, layout V313) |
| Client mixing | Filters 14 + 15 (occupancy + in-flight transports) | **Implemented** (pass 13, shipped 2026-08-16 LF9): real occupancy (`StockUnitLookup.occupantsByLocationIds`, deliberately unscoped) + in-flight transport demand (`TransportDemandLookup` SPI, `OpenPickGuard` direction, zero new Gradle edges) as a second gate after filter 8's cheap `location.clientId` attribute check. Open demand resolves `destinationLocationId` first and otherwise `suggestedLocationId`, so PUTAWAY and TRANSFER suggestions participate alongside MOVE and REPLENISH destinations. RELEASE NOTE: a location owned by the requesting client that physically holds another owner's live stock or is targeted by another owner's open transport is excluded by default (`mixClient` defaults false) |
| Fixed-assignment exclusion | Filter 4 | **Implemented** (pass 12, shipped 2026-08-16 LF8): unconditional SQL `NOT EXISTS` predicate in `findPutawayCandidates`, no knob. RELEASE NOTE: an existing STORAGE-area fix assignment now excludes the location (A8) |
| Item mixing (`strategy.mixItem`) | Filter 19 | **Implemented** (pass 14, shipped 2026-08-16 LF8): batched occupancy + in-flight-transport check, shared reader with client mixing; inert by default (`mixItem` defaults `true`) |
| Rack/aisle constraint for `nearPickingLocation` | Filter 2 | **PARTIAL, upgraded 2026-08-16 (LF8)**: a same-rack ordering PREFERENCE now runs ahead of the X-distance sort (`CandidateOrdering`); the hard same-rack restriction remains deliberately NOT built (A1 ruling - a preference is the faithful single-pass translation of legacy's two-phase rack-then-general search, since a hard in-pass restriction would be strictly worse than legacy: rack full → NoLocation instead of falling back) |
| StorageArea / cluster restriction + hiding | Filters 1 + 16 | **Implemented** (pass 9, shipped 2026-07-25 L1/T3; `useAreaStrategyDate`/`useItemDataArea` hiding) |
| Allocation-state ("mark full") | Filter 9 | **Implemented** (pass 11, shipped 2026-07-25 L3/T6) |
| `strategy.sorts` (9 sort types) + `nearPickingLocation` | Configurable ORDER BY | **Implemented** (shipped 2026-07-25 T5; typed comparator chain + X-distance prepend) |
| `strategy.manualSearch` | Manual-placement bypass | **Implemented** (shipped 2026-07-25 L6, layout V312): short-circuits the whole search to NoLocation; `findAddToLocation` honors the same bypass (LF10) |

---

## 9. Divergence Register (Karyo vs. myWMS) — the gap tracker

**Corrected 2026-08-16, twice in one day.** Six of the eleven v1.2 gaps below shipped 2026-07-25 with the locations-layout sprint (register rows L1-L7); the location-finder sprint (LF8-LF10, same day) then closed three more of the genuine remainder (rows 4, 8, 10 below), and upgraded row 2 from a bare sort preference to an adjudicated, ruled-final partial. **The one genuine gap left among these eleven rows is row 7** (over-size multi-position), adjudicated NOT BUILT (A5) - see its row for why building it would mean inventing behavioral semantics with no sanctioned grounding. The original v1.2 numbering is kept so old references stay resolvable.

| # | myWMS filter (v1.2 status) | Status today |
|---|---|---|
| 1 | **Location cluster membership** (filter 1): restrict to clusters tied to the strategy's storage areas | **SHIPPED 2026-07-25** (L1/T3, `applyAreaConstraints`); no-areas-configured = no restriction, a documented deviation from myWMS's `locationCluster IS NOT NULL` fallback |
| 2 | **Rack constraint** (filter 2): restrict to an aisle for `nearPickingLocation` | **PARTIAL, ruling recorded (A1, 2026-08-16, LF8)**: an X-distance sort preference shipped 2026-07-25 (T5); a same-rack ordering PREFERENCE now runs ahead of it (`CandidateOrdering`, LF8) - the single-pass faithful translation of legacy's two-phase rack-then-general search. The hard same-rack **restriction** is deliberately NOT built: it would be strictly worse than legacy (rack full → `NoLocation` instead of falling back to the general search), so preference is the correct final shape, not an interim step |
| 3 | **UnitLoadType compatibility** (filter 3, `TypeCapacityConstraint`) | **SHIPPED 2026-07-25** (L2/T4, layout V313) |
| 4 | **Fixed-assignment exclusion** (filter 4): exclude locations with a `FixAssignment` | **SHIPPED 2026-08-16** (LF8): an unconditional SQL `NOT EXISTS` predicate in `findPutawayCandidates`. RELEASE NOTE (A8): an existing STORAGE-area fix assignment now excludes the location, no knob |
| 5 | **Allocation-state** (filter 9): exclude locations flagged permanently full | **SHIPPED 2026-07-25** (L3/T6, `allocation_state`) |
| 6 | **Capacity rule** (filter 12): remaining-allocation check against `TypeCapacityConstraint.allocation` | **SHIPPED 2026-07-25** (L2/T4, reservation-inclusive) |
| 7 | **Over-size rule** (filter 13): multi-position ULs needing an adjacent empty neighbor | **PARTIAL, adjudicated NOT BUILT (A5, 2026-08-16)**: oversize (`allocation > 100`) rows exclude any non-empty candidate, a deliberate conservative stand-in; the adjacent-empty placement is not built and will not be built without new behavioral grounding - the corpus names the requirement in one table row and never defines "adjacent," and the real semantics live only in GPL source this project may not read. The stand-in fails safe (never places an oversize UL anywhere legacy would not; only under-utilizes partially-occupied locations). A future customer need is a new register row with a schema design (an adjacency relation), not a parity item |
| 8 | **Transport-order client check** (filter 15): client mixing also against in-flight transports | **SHIPPED 2026-08-16** (LF9): a deliberately-unscoped `StockUnitLookup.occupantsByLocationIds` plus a `TransportDemandLookup` SPI (`karyo-layout-api`, implemented in `karyo-tasks-core`, the `OpenPickGuard` direction, zero new Gradle edges). Open demand covers every non-terminal transport targeting the candidate through `destinationLocationId`, or through `suggestedLocationId` while destination remains null, including PUTAWAY, TRANSFER, MOVE, and REPLENISH. RELEASE NOTE (A4): a location owned by the requesting client that physically holds another owner's live stock, or is targeted by another owner's open transport, is excluded by default (`mixClient` defaults false) |
| 9 | **Hidden areas** (filter 16): exclude areas with older stock / full `ItemDataArea` (`useAreaStrategyDate`, `useItemDataArea`) | **SHIPPED 2026-07-25** (L1/L6/T3) |
| 10 | **Item mixing** (filter 19, `strategy.mixItem`): exclude locations holding/receiving a different product | **SHIPPED 2026-08-16** (LF8): batched occupancy + in-flight-transport pass in `OccupancyMixReader`, sharing its two data sources with row 8; inert by default (`mixItem` defaults `true`) |
| 11 | **`nearPickingLocation` optimization + configurable `strategy.sorts`** (9 sort types incl. `ALLOCATION DESC` consolidation) | **SHIPPED 2026-07-25** (T5, typed comparator chain) |

With rows 2/4/8/10 closed, the finder's built-in pass count moves from 11 to **14** (see §2, passes 12-14); the only row still open is row 7, adjudicated and recorded rather than merely missing. One weight gap sits OUTSIDE this register by accident of history: myWMS's weight filters were treated as fully implemented when these rows were drawn up, but the location-level predicate (filter 5) never counts a candidate's already-occupied weight - see §8's weight row, which owns that gap.

**Additional simplifications documented inline:**

- **Filter 8 (client mixing) → layout-local, upgraded 2026-08-16 (LF9)**: the location-attribute check (`LocationFinderService.clientMixingAllowed`) stays as the cheap first gate. It is no longer the whole check: pass 13's `OccupancyMixReader` adds the real occupancy + in-flight-transport gate described in row 8 above. (The v1.2 claim that the finder issues no cross-module queries into inventory was already no longer literally true: `AreaOccupancyReader` reads `StockUnitLookup.occupancyByLocationIds` for area hiding since 2026-07-25. The client-mixing pass now follows the same pattern - a narrowly-scoped cross-module read for one boolean question, not a general inventory dependency.)
- **`findAddToLocation` (LF10, §2a)** - a wholly separate advisory consolidation search mode, not a divergence from the putaway filter set, so it has no row here by design.
- **Soft reservation (Karyo addition)** — replaces myWMS's transient `locationReserver.checkAllocateLocation()` (filter 18) with a persisted, TTL'd `LocationReservation` so suggestions survive across the suggest→start→complete gap. `findAddToLocation` deliberately does NOT use this mechanism (§2a) - a Karyo-shape divergence in the opposite direction, advisory where putaway is reserved.
