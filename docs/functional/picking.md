# Functional Specification: Order Picking (Discrete Pick Execution)

**Module:** Fulfillment · **Status:** Documents implemented release and execution behavior as verified 2026-09-06
**Audience:** Implementation consultants, support engineers, enterprise evaluators, AI/RAG knowledge base
**Honesty rule:** This spec describes what the **code does today**. Where Karyo implements less than myWMS (its functional ancestor), the gap is stated explicitly — this document doubles as the gap tracker for the picking workflow.
**Source of truth:** code at `services/fulfillment-service/karyo-fulfillment-{api,core}/...`, plus the cross-module SPI seams it consumes in `karyo-inventory-api`, `karyo-orders-api`, `karyo-product-api`, `karyo-layout-api`.

---

## 1. Purpose & Trigger

Picking answers one question: *given a released order whose stock is already reserved, how is that stock physically pulled, tracked, and rolled up so the order can advance to packing?*

It is the fulfillment module's **execution** brain. Picking does **not** decide *which* stock to pull — that decision (the 13-pass FIFO selection, `completeHandling`, `enforceLot`, `preferMatching`) happens earlier, at **order release / reservation time**, and is documented in [stock-selection.md](stock-selection.md). By the time picking runs, the order already carries a set of reservation slices; picking turns those slices into discrete `Pick` work items, executes them, and recovers gracefully when the shelf comes up short.

**When it runs:**

- **Release (primary):** an operator releases a processable order to picking via `POST /api/v1/pick-orders {deliveryOrderId}` → `PickOrderService.releaseToPicking(...)`. The order must be in `PROCESSABLE (300)` (released, stock reserved) and **not yet** at or past `STARTED (500)`.
- **Confirm:** each generated pick is confirmed (full or short) via `POST /api/v1/picks/{id}/confirm {pickedAmount, targetUnitLoadId?}` → `PickOrderService.confirmPick(...)`.

Both endpoints are guarded by the `fulfillment-write` role (`fulfillment-read` for the GET queries). The discrete path releases one `DeliveryOrder` at a time. It normally produces one `PickOrder`, or one per derived picking type when `OrderStrategy.createTypeOrders` is enabled. Wave and batch callers reuse the same persistence and classification mechanics through the fulfillment SPIs.

**The release transaction** (`releaseToPicking`):

1. Resolve the order for picking via `DeliveryOrderLookup.findForPicking(orderId)` (tenant-scoped) — guard `NotFound` (404) if absent.
2. Guard `NotReleasable` (409) if `order.state >= STARTED (500)` — *fail fast* on a re-release rather than do work and lean on `markStarted`'s forward-only guard to roll it back.
3. Flatten the order's per-line reservation slices into one `PlannedPick` each — guard `NotReleasable` (409, "no reserved stock to pick") if there are none.
4. Resolve the PACK_STAGING location via `StagingLocationLookup.findPackStaging(clientId)` — guard `NotReleasable` (409, "no PACK_STAGING location configured") if none.
5. Read each source stock unit's amount once for the full planned set and derive `COMPLETE` versus `PICK` per reservation slice.
6. Group the planned picks via the `PickOrderGroupingStrategy` chain (discrete yields one group).
7. Fire the prepare-event extension seam for each grouping batch, then, when `createTypeOrders` is enabled, split the unconsumed remainder by the already-derived picking type.
8. For each resulting group, create an empty **pick container** UnitLoad at PACK_STAGING, persist a `PickOrder` at `RELEASED (100)`, persist one `Pick` per slice, and append that order's `PickOrderCreated` event.
9. Advance the delivery order to `STARTED (500)` via `OrderProgressionPort.markStarted(orderId, clientId)`.

Pick-order numbers come from `SequenceNumberService` under the `pick.pickOrderNumber` sequence key and the prefix `PO-{orderNumber}`. `KARYO_SEQUENCE_GENERATOR` selects the generator; the default `TIMESTAMP_RANDOM` form appends epoch milliseconds and a three-digit random suffix. Generation enforces the 80-character column limit and retries up to five tenant-scoped uniqueness checks before failing.

---

## 2. The Picking Flow — As Implemented

### 2.1 PickOrder + Pick model

A **`PickOrder`** is the unit of release: it carries the target pick container (`targetUnitLoadId`), the source delivery order, a `state`, and `started`/`finished` timestamps. A **`Pick`** is one unit of execution — **one Pick per reservation slice**. Each `Pick` records its source stock unit, planned amount, picked amount, lot, picking type, and (when it is a recovery pick) `followUpForPickId` / `substitutedItemDataId`.

Picks and PickOrders **share the same state codes** (`PickState`, a subset of the order lifecycle):

| State | Code | Meaning |
|---|---|---|
| `CREATED` | 50 | Entity default (not used by the release path, which persists at RELEASED) |
| `RELEASED` | 100 | Generated, ready to confirm |
| `STARTED` | 500 | PickOrder claimed by an operator through the shared work inbox |
| `PICKED` | 600 | Pick confirmed, or PickOrder terminal with picked work |
| `CANCELED` | 800 | Pick canceled, or PickOrder terminal with no picked work |

`PickState.canAdvanceTo` defines the forward transition and pre-PICKED cancellation rules. The operator work lifecycle additionally claims an unclaimed PickOrder from `RELEASED` to `STARTED` and can release that same claim back to `RELEASED`; individual Pick rows remain `RELEASED` until confirmation or cancellation.

### 2.2 Picking type — COMPLETE vs PICK

Each `Pick` is labelled at generation:

- **`COMPLETE`** — the planned amount equals the *entire* source stock unit's amount (a whole-unit move).
- **`PICK`** — a partial quantity is taken from the source.

The label is set by comparing the slice amount against the source stock unit's amount (looked up through `StockUnitLookup`). Follow-up recovery picks are always labelled `PICK`. This generation-time label is distinct from the stricter complete-handling candidacy checks applied earlier during stock selection.

### 2.3 Pick-order generation

`releaseToPicking` computes the source-amount map from the complete planned-pick set before grouping or extension filtering. `pickingTypeOf` therefore classifies each slice once, and both the persisted `Pick.pickingType` and optional type split consume the same result.

The grouping strategy runs first. The prepare-event seam may then consume picks from each grouping batch. When `createTypeOrders = false`, all remaining picks in that batch stay together, including a mixed COMPLETE/PICK batch. When it is `true`, the remainder is partitioned by the already-known type. A single-type batch still creates one order. Each resulting group receives its own pick-order number, pick container, rows, and outbox event. `POST /api/v1/pick-orders` consequently returns a JSON array in every case.

The pick-order destination is resolved once per release: `DeliveryOrder.destinationLocationId` wins, then `OrderStrategy.defaultDestinationLocationId`, then `null`. Every PickOrder produced from that release receives the same resolved internal warehouse location. This field is not the customer's ship-to address.

### 2.4 The pick-to-container model

Karyo uses a **pick-to-container** model: a confirmed pick physically **lands the stock onto the pick container UnitLoad** and transitions it to `StockState.PICKED (600)`. This is *not* a decrement-at-pick model — the goods dwell on the container in PICKED state, giving in-transit visibility all the way to packing.

Confirm (`confirmPick(pickId, pickedAmount, targetUnitLoadId?)`):

1. Load the `Pick` (tenant-scoped) - `NotFound` (404) if absent; `InvalidPickConfirmation` (422) if already `PICKED` or `CANCELED`.
2. Validate the amount: `pickedAmount` must be in **(0, plannedAmount]** — `InvalidPickConfirmation` (422) otherwise. (A short pick is therefore *accepted*, not rejected — see §3.)
3. Resolve the target container: the explicit `targetUnitLoadId`, else the PickOrder's `targetUnitLoadId`.
4. `StockPicker.pickStock(sourceStockUnitId, pickedAmount, targetUl)` moves the stock and returns the new target stock-unit id. **pickStock consumes the source reservation** for the picked quantity (it releases `reservedAmount` before transferring, because `transferStock` validates `availableAmount`) — so fulfillment never touches reservations directly. For an aggregating Pick Bin it is **accumulation-safe** (the underlying state change is guarded `if state != PICKED`, so merging into an already-PICKED unit does not re-flip a forward-only state).
5. Stamp `pickedAmount` / `targetStockUnitId`, set the Pick to `PICKED (600)`.
6. If `pickedAmount < plannedAmount`, run `handleShortfall(...)` (§3).
7. **Roll-up:** re-read all picks for the PickOrder. When every pick, including follow-ups generated by step 6, is terminal (`PICKED` or `CANCELED`), set the PickOrder to `PICKED (600)` and stamp `finished`. A discrete delivery order advances through `OrderProgressionPort.markPicked(...)` only when every sibling PickOrder created for that delivery order is also terminal; completing the first sibling of a type-split release leaves the delivery order at `STARTED`. Append a `PickOrderPicked` event for the completed PickOrder.

Follow-up recovery picks are confirmed through the **same** `/picks/{id}/confirm` endpoint — there is no separate recovery API.

---

## 3. Short-Pick Recovery

A confirm where `pickedAmount < plannedAmount` is **accepted**, not an error. The picked portion lands on the container; the shortfall is recovered through `handleShortfall(...)`, governed by **three pluggable knobs** resolved per order via `OrderStrategyLookup.findPickingStrategy(orderId)` (`PickingStrategyView`). All three are ADR-036 strategy seams.

### 3.1 The three knobs

| Knob | Type | Default | Governs |
|---|---|---|---|
| `shortPickMode` | typed enum (`ShortPickMode`, orders module) | `FOLLOW_UP_THEN_SUBSTITUTE` | The **cover-attempt order** for the shortfall |
| `pickDifferenceStrategy` | SPI + name knob | `LEAVE` | What happens to the **short source's residual** |
| `shortfallStrategy` | SPI + name knob | `PARTIAL_SHIP` | The **uncovered remainder** after the cover attempt |

**`shortPickMode`** (`FOLLOW_UP` / `FOLLOW_UP_THEN_SUBSTITUTE` / `SUBSTITUTE_ONLY` / `NONE`):

- `FOLLOW_UP` — re-select remaining same-item stock for the shortfall; create follow-up picks.
- `FOLLOW_UP_THEN_SUBSTITUTE` (default) — follow-up first, then 1:1 substitution for any still-uncovered remainder.
- `SUBSTITUTE_ONLY` — skip follow-up; cover only via 1:1 substitution.
- `NONE` — no cover attempt; the whole shortfall goes straight to the `ShortfallStrategy`.

An unrecognised / null mode parses to the default (`FOLLOW_UP_THEN_SUBSTITUTE`).

### 3.2 The cover flow (`handleShortfall`)

1. **Free the leftover reservation** on the short source: `StockPicker.releaseUnpickedReservation(sourceStockUnitId, shortfall)` — the goods were reserved but are not physically present, so the reservation must not linger.
2. **Resolve the source residual** via the `PickDifferenceStrategy` chain. The built-in `LeaveDifferenceStrategy` (`LEAVE`) leaves the stock on the bin untouched but returns the source stock-unit id in `excludeStockUnitIds`, so the follow-up re-selection skips it. (The phantom is reconciled later by cycle-count.)
3. **Cover per `shortPickMode`:**
   - **Follow-up re-selection** (`coverWithFollowUps`): `StockReserver.reserve(...)` with `excludeStockUnitIds = [short source]`, the order's selection knobs forwarded (`useLockedStock`, `preferComplete`, `preferMatching`, `completeHandling`, `enforceLot`), and `correlationId = pickOrderNumber` for the journal. Each returned reservation slice becomes a **follow-up `Pick`** (state `RELEASED`, `followUpForPickId` = the short pick). For a **same-item** follow-up the parent's lot is honored (re-selected within and stamped); a **substitute** is a different item with its own lots, so neither lot-restricted nor lot-stamped.
   - **1:1 substitution**: `SubstitutionLookup.findSubstitutes(itemDataId)` returns active substitutes ordered by priority; each is covered through the same `coverWithFollowUps`, with `substitutedItemDataId` stamped on the resulting picks.
4. **Remainder** (still uncovered after the cover attempt): the `ShortfallStrategy` chain. The built-in `PartialShipShortfallStrategy` (`PARTIAL_SHIP`) writes a `PickShortfallReported` event to the outbox and accepts the shortage (the order is allowed to complete short).

### 3.3 The FIFO-phantom-residual bug (lesson)

The exclusion seam exists because follow-up re-selection otherwise re-grabs the just-freed phantom residual on the short source: FIFO puts that source first again, so substitution or partial-shipment handling is never reached. `LeaveDifferenceStrategy` places the current source in `ReservationRequest.excludeStockUnitIds`, and `StockSelectionService.getCandidatesForPass` removes it from the baseline 13-pass candidates. Exclusion is per-confirm, not cumulative: a later short follow-up names only its own source.

The current selector has one important limit: its `preferMatching` and `completeHandling` pre-scans do not apply `excludeStockUnitIds`. The exclusion guarantee therefore holds for the default baseline path, but a strategy enabling either pre-scan can reselect the short source. Future difference strategies that write off or quarantine the residual would make it unselectable independently of this request-level exclusion.

---

## 4. State & Reservation Semantics

**Pick and claim lifecycle.** Picks are generated at `RELEASED (100)` and move to `PICKED (600)` on confirmation. The shared work inbox claims the owning PickOrder from `RELEASED` to `STARTED (500)`, records the operator, and can release the same claim back to the unclaimed `RELEASED` pool. Confirmation accepts either pre-terminal PickOrder work state.

**Cancellation.** `POST /api/v1/pick-orders/{id}/picks/{pickId}/cancel` releases that open pick's outstanding reservation and marks it `CANCELED`; confirming the remaining picks may still complete the PickOrder. `POST /api/v1/pick-orders/{id}/cancel` force-finishes all open picks, clears the claim, and leaves the PickOrder `CANCELED` when nothing was picked or `PICKED` when it retains picked work. Both routes reject already terminal work and enforce the PickOrder claim unless the caller has the manager override.

**Reservation consumption is at pick, not at release.** Release leaves the order's reservations intact; `StockPicker.pickStock` consumes `reservedAmount` for the picked quantity as part of the move. A short pick or canceled open pick explicitly releases its outstanding reservation. Net: a fully picked line's reservations are consumed, while short or canceled portions are freed and any follow-up re-reserves elsewhere.

**Order roll-up.** A PickOrder becomes `PICKED (600)` when all of its picks are terminal (`PICKED` or `CANCELED`), including follow-ups generated mid-confirm. Because follow-ups are persisted as `RELEASED` inside the same confirm transaction, the roll-up sees them and holds that PickOrder open. For a direct non-wave release, the delivery order advances only after every sibling PickOrder is terminal; this prevents a `createTypeOrders` release from advancing when its first COMPLETE or PICK sibling finishes.

### 4.1 Downstream progression flags

Three order-strategy flags control what happens around the picking result without disabling the underlying operations:

- `sendToPacking = true` parks a picked delivery order at `PACKING(640)` rather than `PICKED(600)`. Packing remains available when the flag is false.
- `sendToShipping = true` parks a packed order at `SHIPPING(670)` rather than `PACKED(650)`. Karyo deliberately defaults this flag to `false` so existing orders do not silently change resting state.
- `createShippingOrder = true` asks fulfillment to open packing automatically when pick-order completion fires. A refusal such as missing PACK_STAGING configuration is swallowed and leaves manual packing available.

These are state parking and convenience controls. They do not decide whether physical picking, packing, or shipping is permitted.

**Outbox events** (delivery contract: [webhook catalog](../integration/webhook-event-catalog.md)):

| Event | When |
|---|---|
| `PickOrderCreated` | on release (`PickOrder`, carries order id, client, pick count) |
| `PickOrderPicked` | on terminal roll-up to PICKED (`PickOrder`) |
| `PickOrderCanceled` | on force-finish cancellation, with canceled and retained-pick counts |
| `PickOrderReleased` | on a claimed order being handed back to the pool (STARTED -> RELEASED), carrying `releasedFrom`, `releasedBy` and `managerOverride` |
| `PickShortfallReported` | on a `PARTIAL_SHIP` remainder (`Pick`, carries the uncovered shortfall + resolution) |

All five carry the `clientId` and are written through `OutboxService.publish`.

---

## 5. Strategy Flags & Configuration — implemented vs. myWMS

Picking is driven by the **picking knobs on the order's `OrderStrategy`**, resolved at confirm via `OrderStrategyLookup.findPickingStrategy` into a `PickingStrategyView`. There are two flavours: a **typed enum knob** (`shortPickMode`), and **SPI-name knobs** (`pickDifferenceStrategy`, `shortfallStrategy`, plus the grouping seam) where the string names a registered strategy bean.

| Lever | Type | Default | myWMS analogue | Karyo today |
|---|---|---|---|---|
| `shortPickMode` | typed enum | `FOLLOW_UP_THEN_SUBSTITUTE` | follow-up / substitution flags on `OrderStrategy` | **Implemented** — 4 modes (§3) |
| `pickDifferenceStrategy` | SPI name | `LEAVE` | stock-difference handling on short pick | **Implemented** as `LEAVE`; `WRITE_OFF`/`QUARANTINE` are future beans |
| `shortfallStrategy` | SPI name | `PARTIAL_SHIP` | backorder / partial-ship policy | **Implemented** as `PARTIAL_SHIP`; `PENDING_ESCALATION`/auto-recovery are future beans |
| Pick grouping | SPI (`PickOrderGroupingStrategy`) | discrete | wave / batch / cluster / zone picking | **Implemented as a seam** - direct release persists every resolved group and may further split it by COMPLETE/PICK type; wave and batch generation use fulfillment SPIs |

**Selection knobs are forwarded, not re-decided.** `useLockedStock`, `preferComplete`, `preferMatching`, `completeHandling`, `enforceLot` ride along on the `PickingStrategyView` and are passed into the follow-up `ReservationRequest` so the recovery re-selection obeys the *same* selection policy as the original release — see [stock-selection.md §5](stock-selection.md) for what each of those does. Picking itself does not interpret them.

---

## 6. Worked Examples

All examples: client 1, a single delivery order in `PROCESSABLE (300)`, a PACK_STAGING location configured, defaults (`shortPickMode = FOLLOW_UP_THEN_SUBSTITUTE`, `pickDifferenceStrategy = LEAVE`, `shortfallStrategy = PARTIAL_SHIP`).

### Example A — full happy-path single-order pick

Order *DO-1* has one line, demand **100** of *WIDGET*, reserved at release as a single slice on stock unit S1 (S1 holds exactly 100).

Release creates `PickOrder PO-DO-1-…` (RELEASED) + one `Pick` (RELEASED, planned 100). Because the slice equals S1's full amount, the pick is labelled **COMPLETE**. A pick container is created at PACK_STAGING; the order goes to `STARTED (500)`.

Confirm `pickedAmount = 100`: `pickStock` moves 100 off S1 onto the container (consuming the reservation), the pick → `PICKED`. All picks PICKED → PickOrder → `PICKED`, **order → `PICKED (600)`**, `PickOrderPicked` emitted. **Result: clean full pick, order ready for packing.**

### Example B — short pick covered by a follow-up

Same order, but the line demands **100** reserved across S1 (60) and S2 (40) → two picks. The operator confirms the S1 pick (planned 60) with **pickedAmount = 50** — 10 short.

`handleShortfall(10)`: free the 10 leftover reservation on S1 → `LEAVE` excludes S1 → follow-up `StockReserver.reserve(WIDGET, 10, exclude=[S1])` finds 10 on S3 → a **follow-up Pick** (RELEASED, `followUpForPickId` = the S1 pick) for 10 from S3. The S1 pick is now `PICKED`; the order stays open (S2 pick + the new S3 follow-up are still RELEASED). The operator confirms S2 (40 → PICKED) and the S3 follow-up (10 → PICKED). All picks PICKED → **order → `PICKED`**. **Result: the 10-unit shortfall was silently covered from other stock; the order completes in full.**

### Example C — follow-up exhausted, 1:1 substitution covers the rest

Same short of **10** on S1, but no other *WIDGET* stock exists. Follow-up re-selection returns `shortfall = 10` (nothing reservable). Because the mode is `FOLLOW_UP_THEN_SUBSTITUTE`, `SubstitutionLookup.findSubstitutes(WIDGET)` returns *WIDGET-ALT* (priority 1). `coverWithFollowUps(WIDGET-ALT, 10, substituteItemDataId=WIDGET-ALT)` reserves 10 of WIDGET-ALT → a **follow-up Pick** with `substitutedItemDataId = WIDGET-ALT` (lot **not** restricted or stamped — a substitute has its own lots). **Result: the shortfall is covered by the approved substitute; the order completes once that follow-up is confirmed.**

### Example D — uncovered remainder → PARTIAL_SHIP

Same short of **10**, no other WIDGET stock and no substitute. Both cover attempts return the full 10 as remaining. The `ShortfallStrategy` chain resolves to the built-in `PARTIAL_SHIP`: a `PickShortfallReported` event (shortfall 10, resolution `PARTIAL_SHIP`) is written to the outbox and the shortfall is **accepted**. The S1 pick (50 picked) is `PICKED`; once the order's remaining picks are confirmed, **the order completes short** — it ships what was pulled. **Result: no follow-up, no substitute, no block — the order finishes with a recorded shortage** (a future `PENDING_ESCALATION` strategy would instead hold/backorder the line).

---

## 7. SPI Seams (Extension Points)

**Picking-domain seams** (in `karyo-fulfillment-api`), all ADR-036 strategy-SPIs resolved by a priority-ordered, first-non-null / name-matched resolver with a built-in registered at `Int.MAX_VALUE` (runs last, always answers):

- **`PickOrderGroupingStrategy`** — decides how a released order's reserved work is grouped/sequenced into PickOrders. Built-in `DiscreteGroupingStrategy` yields one group per order. Resolver `PickOrderGroupingResolver` (ascending priority, first non-null). **This is the same seam ADR-035 designs for wave-based fulfillment** — batch / cluster / zone / wave grouping compose here later *without a PickOrder refactor*.
- **`PickDifferenceStrategy`** — what happens to a short pick's source residual. Built-in `LeaveDifferenceStrategy` (`LEAVE`) excludes the source from re-selection. Future `WRITE_OFF` (decrement the source as a stock difference) / `QUARANTINE` (lock the residual for recount) register as beans and win by name/priority.
- **`ShortfallStrategy`** — the terminal handler for an uncovered remainder. Built-in `PartialShipShortfallStrategy` (`PARTIAL_SHIP`) reports + accepts. Future `PENDING_ESCALATION` / auto-recovery register as beans.

Resolvers prefer a strategy whose `name` matches the order's knob, else fall back to the first non-null by ascending priority — so a deployment selects behavior per order via the knob, or globally via a higher-priority bean.

**Cross-module SPI seams consumed** (each contracted in a sibling `api` module so fulfillment-core never depends on a foreign core):

| Seam (module) | Used for |
|---|---|
| `StockPicker` (inventory) | `createPickContainer`, `pickStock` (move + consume reservation → PICKED), `releaseUnpickedReservation` (free a short remainder) |
| `StockReserver` / `StockSelectionService` (inventory) | follow-up re-selection via `ReservationRequest` (incl. `excludeStockUnitIds`, `correlationId = pickOrderNumber`) |
| `StockUnitLookup` (inventory) | source amounts for COMPLETE/PICK labeling |
| `DeliveryOrderLookup.findForPicking` (orders) | the order header + lines + reservation slices to pick |
| `OrderProgressionPort` (orders) | `markStarted (500)` at release, `markPicked (600)` on roll-up |
| `OrderStrategyLookup.findPickingStrategy` (orders) | resolve the order's picking + selection knobs |
| `SubstitutionLookup.findSubstitutes` (product) | priority-ordered 1:1 substitutes during recovery |
| `StagingLocationLookup.findPackStaging` (layout) | the PACK_STAGING location for the pick container |

---

## 8. Code Pointers

| What | Where |
|---|---|
| Picking algorithm (release, confirm, roll-up, short-pick recovery, cover flow, mode parsing) | `services/fulfillment-service/karyo-fulfillment-core/.../service/PickOrderService.kt` — `releaseToPicking()`, `confirmPick()`, `handleShortfall()`, `coverWithFollowUps()`, `parseMode()`, `flattenReservations()`, `sourceAmountsFor()` |
| Entities | `.../domain/model/PickOrder.kt`, `.../domain/model/Pick.kt`; migration `services/karyo-app/.../db/migration/fulfillment/V601__create_pick_orders.sql` |
| Value objects | `karyo-fulfillment-api/.../vo/PickState.kt`, `.../vo/PickingType.kt` |
| Grouping seam + built-in + resolver | `karyo-fulfillment-api/.../spi/PickOrderGroupingStrategy.kt`; `.../service/DiscreteGroupingStrategy.kt`, `.../service/PickOrderGroupingResolver.kt` |
| Pick-difference seam + built-in + resolver | `.../spi/PickDifferenceStrategy.kt`; `.../service/LeaveDifferenceStrategy.kt`, `.../service/PickDifferenceStrategyResolver.kt` |
| Shortfall seam + built-in + resolver | `.../spi/ShortfallStrategy.kt`; `.../service/PartialShipShortfallStrategy.kt`, `.../service/ShortfallStrategyResolver.kt` |
| Short-pick mode enum | `services/order-service/karyo-orders-api/.../vo/ShortPickMode.kt`; knob migrations `orders/V410…V412` |
| REST + DTOs + RFC-7807 mapper | `.../api/v1/PickOrderResource.kt`, `.../api/v1/dto/PickDtos.kt`, `.../exception/FulfillmentException.kt`, `.../exception/FulfillmentExceptionMapper.kt` |
| Cross-module SPIs consumed | `karyo-inventory-api/.../spi/StockPicker.kt`, `StockReserver.kt` (`ReservationRequest.excludeStockUnitIds`), `StockUnitLookup.kt`; `karyo-orders-api/.../spi/DeliveryOrderLookup.kt`, `OrderProgressionPort.kt`, `OrderStrategyLookup.kt`; `karyo-product-api/.../spi/SubstitutionLookup.kt`; `karyo-layout-api/.../spi/StagingLocationLookup.kt` |
| Tests | `services/karyo-app/src/test/kotlin/com/karyo/fulfillment/...` |
| Upstream selection (reservation) | [stock-selection.md](stock-selection.md), especially §3-§5 |

---

## 9. Divergence Register (Karyo vs. myWMS) — the gap tracker

| # | Divergence | Status |
|---|---|---|
| 1 | **Discrete single-order picking** - one delivery order released at a time | **Implemented**; normally one PickOrder, or one per COMPLETE/PICK type when `createTypeOrders` is enabled (§2.3) |
| 2 | **Short-pick recovery** (follow-up re-selection + 1:1 substitution + partial-ship) | **Implemented** (3.2b-2) |
| 3 | **Wave / batch / zone picking** | **Implemented on the wave path** through `BatchPickPort`; the direct `releaseToPicking` path remains discrete and accepts multiple grouping results |
| 4 | **Stock-difference handling on short pick** (`WRITE_OFF`, `QUARANTINE`) | **Seam-shipped** — `PickDifferenceStrategy` present, only `LEAVE` (exclude-from-reselection) built |
| 5 | **Backorder / escalation on uncovered remainder** (`PENDING_ESCALATION`, auto-recovery) | **Seam-shipped** — `ShortfallStrategy` present, only `PARTIAL_SHIP` built |
| 6 | **Pick-to-cart / multi-tote / pick-by-zone routing** (operator carries multiple containers) | **Deferred** — single pick container per PickOrder |
| 7 | **Assign / start operator lifecycle** (claim and release a PickOrder) | **Implemented** through the shared work inbox: claim moves RELEASED to STARTED and release returns it to RELEASED |
| 8 | **COMPLETE candidacy = area / fixed-location validation** (myWMS strict checks before a pick is COMPLETE) | **Deferred to Spec B** — v1.3 COMPLETE/PICK is whole-unit-vs-partial only (§2.2; mirrors [stock-selection.md §4](stock-selection.md)) |
| 9 | **Pick cancellation workflow** | **Implemented** for individual picks and whole PickOrders, with reservation release, claim ownership checks, and force-finish outcomes (§4) |
| 10 | **Selection decisions** (13-pass FIFO, `completeHandling`, `enforceLot`, `preferMatching`) | **Documented separately** — happen at reservation time; see [stock-selection.md](stock-selection.md). Picking forwards these knobs into follow-up re-selection but does not re-decide them |

**Additional notes documented inline:**

- **Pick-to-container, not decrement-at-pick** (Karyo model) — picked stock physically dwells on the container at `PICKED (600)` for in-transit visibility; `pickStock` is accumulation-safe for aggregating Pick Bins (guard `if state != PICKED`).
- **Reservation consumed at pick** — release leaves reservations intact; `StockPicker.pickStock` consumes them, and a short pick frees its leftover via `releaseUnpickedReservation` before recovery.
- **Exclusion is per-confirm** - the FIFO-phantom-residual guard (§3.3) names only the current short source. It applies to baseline passes; exact-match and complete-handling pre-scans are the documented limitation.
- **`pickOrderNumber` generation** - `SequenceNumberService` uses the configured generator, enforces the 80-character limit, and retries tenant-scoped uniqueness checks up to five times.
