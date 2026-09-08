# Functional Specification: Stock Selection (Picking Stock Determination)

**Module:** Inventory · **Status:** Documents implemented behavior as verified 2026-08-30
**Audience:** Implementation consultants, support engineers, enterprise evaluators, AI/RAG knowledge base
**Honesty rule:** This spec describes what the **code does today**. Where Karyo implements less than myWMS (its functional ancestor), the gap is stated explicitly — this document doubles as the gap tracker for the selection algorithm.

---

## 1. Purpose & Trigger

Stock selection answers one question: *given a demand for a quantity of a product, which physical stock should be picked, and in what order?*

It is the inventory module's allocation brain. Karyo independently implements the established 13-pass selection behavior while making every supported rule and divergence explicit in this specification (see §3, §5, and §9).

**When it runs:**

- Order release and short-pick recovery call the in-process `StockReserver`, whose default implementation delegates to `StockSelectionService.selectStock(...)` before reserving the returned slices in the same transaction.
- Direct selection is **advisory and read-only**: it does not reserve, lock, or move anything. Tests and extension code may call the service directly. There is no REST selector or reservation route under `/api/internal`; that service-to-service surface was removed after the modular-monolith pivot.

**Inputs** (`StockSelectionRequest`):

| Input | Required | Meaning |
|---|---|---|
| `itemDataId` | yes | The product being demanded |
| `amount` | yes | Quantity demanded (decimal) |
| `clientId` | yes | Goods owner — all selection is scoped to one client (see §2) |
| `lotNumber` | no | Preferred lot/batch (a preference, not a hard constraint unless `enforceLot` is true; see §3 and §6 Example C) |
| `enforceLot` | no (default false) | When a lot is supplied, skip every any-lot fallback in the baseline passes |
| `useLockedStock` | no (default false) | Admit locked stock in baseline passes 10-11 and in the strict complete-handling pool |
| `preferComplete` | no (default true) | Prefer a single stock unit that covers the whole demand |
| `preferMatching` | no (default false) | Run an exact-available-amount pre-scan before the normal passes |
| `completeHandling` | no (default 0 = NONE) | Select strict complete unit loads using one of the five complete-only modes in §5 |
| `excludeStockUnitIds` | no (default empty) | Remove named units from the baseline 13-pass candidates; the exact-match and complete-handling pre-scans do not currently apply this exclusion |

**Output** (`StockSelectionResponse`): an ordered list of picks (`stocks`), each with the stock unit, its unit load and location, the `suggestedPickAmount`, and a `pickingType` of `COMPLETE` or `PICK` (see §4); plus `totalAvailable` (sum of available quantity on the *selected* units) and `fullyFulfilled`. **A partial result is a valid result** — when the warehouse cannot cover the demand, the response carries whatever could be allocated and `fullyFulfilled = false`. The caller decides whether a short allocation is acceptable.

---

## 2. Preconditions & Exclusions — stock that is never considered

Before any pass runs, and inside every candidate query, the following stock is excluded:

1. **Inactive products — hard stop.** If the requested product is marked inactive for this client, selection returns an empty, unfulfilled response immediately. The inventory module maintains its own *inactive-product projection* (`InactiveProduct` table): when the product module changes a product's state, it fires a synchronous CDI event (`ItemDataStateChangedEvent`, state 700 = inactive, 100 = active) and the inventory module's observer inserts or deletes the projection row in the same transaction. Selection therefore never needs to call the product module.
2. **Stock not on stock.** Only stock units in state `ON_STOCK (300)` are candidates. Incoming, picked, packed, shipped, or deletable stock is invisible to selection.
3. **Reserved quantity.** The fundamental availability rule: `availableAmount = amount − reservedAmount`, never negative. Candidate queries require `amount > reservedAmount`, and each unit is re-checked during accumulation; a unit with zero availability is skipped.
4. **Locked stock.** Stock with any lock (`lockType ≠ 0`) is excluded from the baseline search unless `useLockedStock = true`, which admits it in passes 10-11 after the unlocked passes. The exact-match pre-scan always excludes locked stock. Strict complete handling uses `useLockedStock` directly on its one candidate query, so locked and unlocked complete candidates share FIFO ordering there rather than forming separate fallback tiers.
5. **Other clients' stock.** Every query is scoped to the requesting `clientId` (the goods owner — myWMS meaning of client). Under Karyo's silo tenancy a 3PL instance holds several goods owners; their stock pools never mix in selection.

**Location rules:** area suitability and fixed-location candidacy are not general selection filters. The selector does enforce a matching `FixAssignment.maxPickAmount`: when the remaining demand exceeds that ceiling, the fixed slot is skipped for that pass and may become eligible after other picks reduce the remainder. STORAGE/PICKING-area checks and packaging-unit rounding remain gaps (see §9).

---

## 3. The Pass Sequence

Selection first resolves the strategy-specific pre-passes. With `preferMatching=true`, the first eligible unlocked unit whose available amount exactly equals demand wins unless complete handling is active. Any non-NONE `completeHandling` mode instead searches strict complete unit loads, returns that mode's result, and never falls through to partial selection. Strict complete means unopened, wholly unreserved, single-stock, and backed by a unit-load type that permits COMPLETE usage; `useLockedStock` controls whether its pool may also contain locked candidates.

When neither pre-pass finishes the request, selection runs up to 13 passes in order, accumulating picks until the demand is covered. Each pass produces a fresh candidate list (FIFO-ordered, §4), the SPI filter chain prunes or reorders it (§7), and the algorithm walks the survivors taking `min(available, remaining)` from each. Excluded IDs apply to the baseline passes, while the fixed-slot pick ceiling applies to the baseline, exact-match, and complete-handling paths. Units picked in an earlier baseline pass are never picked twice. The loop stops the moment `remaining ≤ 0`.

The baseline 13 passes are:

| Pass | Gate (skipped unless…) | Candidate scope | Lot filter |
|---|---|---|---|
| 1 | `preferComplete = true` | Units whose available quantity covers the **entire requested amount** | requested lot |
| 2 | `preferComplete = true` | same | none |
| 3 | `preferComplete = true` | same | requested lot |
| 4 | `preferComplete = true` | same | none |
| 5 | `preferComplete = true` | same | none |
| 6 | always | Any available unit (partial picks allowed) | requested lot |
| 7 | always | same | none |
| 8 | always | same | requested lot |
| 9 | always | same | none |
| 10 | `useLockedStock = true` | Any available unit **including locked** | requested lot |
| 11 | `useLockedStock = true` | same | none |
| 12 | always (fallback) | Any available unlocked unit | requested lot |
| 13 | always (fallback) | same | none |

Behavioral consequences worth understanding:

- **"Complete" in passes 1–5 means *coverage*, not unit-load completeness.** A unit qualifies if its available quantity ≥ the requested amount. Whether the resulting pick is a clean full-unit-load pick is decided afterwards, per pick, as the `pickingType` label (§4). This differs from myWMS, where complete-handling passes validate the unit load itself (unopened, unreserved, single-stock, COMPLETE-capable type, in a STORAGE area, not on a fixed location) before the unit is even a candidate.
- **Passes 1–5 yield at most one pick** — by construction the first surviving candidate covers the whole remaining demand.
- **Lot is a preference with fallback by default.** Lot-filtered and unfiltered baseline passes alternate, so if the requested lot cannot satisfy the demand, selection crosses into other lots (Example C). Setting `enforceLot=true` with a supplied lot skips every any-lot baseline pass and returns short rather than crossing lots. Complete-handling modes always apply a supplied lot directly to their single strict candidate query and never fall back to another lot, regardless of `enforceLot`.
- **Locked stock is last-resort in the baseline loop** and only on explicit request. Passes 10-11 query locked and unlocked stock together; in practice the unlocked units were already consumed by earlier passes. Complete-only handling is the exception described above: when `useLockedStock=true`, its single strict pool does not prioritize unlocked candidates.
- **The baseline loop has six distinct repository scopes.** Passes 3-5 repeat 1-2, passes 8-9 repeat 6-7, and passes 12-13 repeat 6-7. Already-selected units make repeated results harmless. The strategy distinctions for `preferMatching`, `completeHandling`, strict lot, exclusions, and fixed-slot ceilings are implemented outside or around those repository scopes; area-specific passes remain absent.

---

## 4. Ordering, Tie-Breaking & Picking Type

**Within every pass**, candidates are ordered by the canonical FIFO rule, identical to myWMS:

1. `strategyDate` ascending — oldest first. This is the FIFO anchor; it defaults to the receipt date but is a settable field, so FEFO-style behavior (set it to expiry) is possible per stock unit.
2. `amount` ascending — smallest first among equal dates, so small remnant stocks are emptied before large ones (reduces fragmentation).
3. `created` ascending, then `id` ascending — deterministic tie-breakers; two identical requests always produce the same answer.

**Picking type per pick.** Each suggested pick is labeled:

- `COMPLETE` — the pick can travel as a whole unit load. Requires **all four**: the pick takes the unit's exact full amount; the unit load has never been opened; the unit load carries only this one stock unit; the unit load's type allows COMPLETE usage.
- `PICK` — anything else: a partial quantity, an opened or mixed unit load, or a type without COMPLETE usage. The operator picks the quantity off the unit load.

In the baseline and exact-match paths, these checks label the result without excluding a candidate. In every non-NONE `completeHandling` mode, the unopened, wholly unreserved, single-stock, COMPLETE-capable subset of the same checks gates candidacy before the mode chooses any unit loads.

---

## 5. Strategy Flags & Configuration — implemented vs. myWMS

myWMS drives selection through `OrderStrategy` (13 flags) plus the lot/strategy date. Karyo's current surface:

| Strategy lever | myWMS | Karyo today |
|---|---|---|
| `preferComplete` | Pass 7/9 preference; pass 10 FIFO-preserving variant when false | **Implemented**, but as *coverage* preference (§3); the FIFO-preserving pass-10 rule when `preferComplete=false` is **not** implemented |
| `useLockedStock` | Locked stock usable per strategy | **Implemented** in baseline passes 10-11 and as an inclusion switch for strict complete handling |
| Lot targeting | Lot is part of the candidate criteria | **Implemented as preference with cross-lot fallback** (§3); strict mode via `enforceLot` (below) |
| `enforceLot` (strict lot) | (myWMS lot is hard criteria) | **Implemented (v1.3 sub-phase 3.1)** — when set with a lot, the any-lot passes are skipped so selection never crosses lots (returns short instead) |
| `preferMatching` (exact-amount match wins) | Passes 1, 7, 8 | **Implemented (v1.3 sub-phase 3.1)** — exact-amount pre-scan; Example A now selects the exact unit. Subordinate to `completeHandling` when both are set |
| `completeHandling` modes (`AMOUNT_FIRST_MATCH`, `AMOUNT_MATCH`, `AMOUNT_FIRST_PLUS`, `AMOUNT_SMALLEST_DIFF`, `AMOUNT_SMALLEST_PLUS`) with combinatorial Optimizer | Passes 2–6, incl. early exit "complete or nothing" | **Implemented (v1.3 sub-phase 3.1)** — all 5 modes + the bounded combinatorial `Optimizer` + complete-or-nothing early-exit; strict-complete candidacy (minus the location checks) |
| Fixed picking locations, `maxFixPickAmount`, packaging-unit rounding | Passes 11-12 | **Partially implemented**: a matching `FixAssignment.maxPickAmount` is a soft per-pick ceiling; specialized fixed-location passes and packaging rounding are absent |
| Area checks (STORAGE/PICKING) | Candidate validation | **Not implemented** |
| Remaining `OrderStrategy` flags (follow-up picks, unit-load creation, etc.) | Various workflows | **Implemented by their owning workflows where documented**; they do not alter stock selection |

The selector consumes explicit request fields. Normal order release resolves those fields from the order's `OrderStrategy`; direct in-process callers provide them themselves. Deployed CDI `StockSelectionFilter` beans can add policy without changing the request contract (§7).

---

## 6. Worked Examples

All examples: product *WIDGET*, client 1, all units in state ON_STOCK, no reservations unless stated, each unit load unopened, single-stock, type with COMPLETE usage.

### Example A - coverage preference and exact-match override

Demand: **100**, defaults (`preferComplete=true`).

| Unit | Unit load | strategyDate | Available |
|---|---|---|---|
| S1 | UL-A | Mar 01 | 60 |
| S2 | UL-B | Mar 05 | 120 |
| S3 | UL-C | Mar 10 | 100 |

Pass 1 (complete coverage): candidates with available ≥ 100 are S2 and S3; FIFO puts S2 first (older). **Result: one pick — 100 from S2 (`PICK`, since 100 ≠ 120), fully fulfilled.** UL-B is broken open; S1's older stock is bypassed.

With `preferMatching=true`, Karyo's exact-match pre-scan selects **S3** instead. `completeHandling=AMOUNT_FIRST_MATCH` also selects S3 when it is a strict complete unit load; if no strict exact match exists, that complete-only mode returns no picks rather than falling through to S2.

### Example B — strict FIFO accumulation with `preferComplete=false`

Same stock, demand **100**, `preferComplete=false`.

Passes 1–5 are skipped. Pass 6 walks pure FIFO: take all 60 from S1 (exact full unit → `COMPLETE`), then 40 from S2 (`PICK`). **Result: two picks (S1:60 + S2:40), fully fulfilled, strict FIFO preserved.**

*myWMS contrast:* same allocation in substance, but myWMS returns one stock unit per call and the caller iterates; Karyo accumulates the whole allocation in a single call.

### Example C — lot is a preference, not a fence

Demand: **50** of lot **LOT-7**, defaults (`preferComplete=true`).

| Unit | Lot | strategyDate | Available |
|---|---|---|---|
| S4 | LOT-7 | Mar 02 | 30 |
| S5 | LOT-9 | Mar 03 | 100 |
| S6 | LOT-7 | Mar 08 | 10 |

Pass 1 (complete + LOT-7): no LOT-7 unit covers 50 → empty. Pass 2 (complete, **any lot**): S5 covers 50. **Result: one pick — 50 from S5, lot LOT-9.** The requested lot is bypassed entirely even though 40 units of LOT-7 sit on the shelf, because complete-coverage preference outranks lot preference in the pass order.

With `preferComplete=false` the outcome changes: pass 6 (partial + LOT-7) takes S4:30 and S6:10, pass 7 tops up 10 from S5 - requested lot is exhausted first and another lot covers only the remainder. With `enforceLot=true`, every any-lot pass is skipped and the response stays short at 40 instead of selecting S5.

### Example D — locked stock as last resort

Demand: **80**, `useLockedStock=true`. S7: unlocked, Mar 01, 50 available. S8: locked (QA hold), Feb 20, 100 available.

Passes 1–5: no unlocked unit covers 80 → nothing. Pass 6: S7 → pick 50, remaining 30. Pass 10 (locked now eligible): S8 → pick 30. **Result: S7:50 + S8:30, fulfilled.** Note S8 is *older* yet still chosen last — lock status outranks FIFO. With `useLockedStock=false` the response would be S7:50, `fullyFulfilled=false`.

---

## 7. Extension Points

- **`StockSelectionFilter` SPI** (in `karyo-inventory-api`) — the supported way to inject custom allocation policy. The filter chain runs on **every pass**: each filter receives the pass's candidate stock-unit IDs plus the full request and returns the subset to keep. Filters are CDI beans discovered from any JAR on the classpath, ordered by `priority()` ascending (lower runs first; default 1000). Deploy in a client extension JAR compiled against the `api` module only, annotated `@Alternative @Priority(...)`.
  - **Filter *and* re-rank (since the filter-order correction, 2026-06-13):** the core now returns survivors **in the order the filter chain produced**, so a filter can both veto and re-rank candidates — matching layout's `LocationFilter` and the SPI contract. (Previously the core re-imposed the original FIFO order, discarding any reordering; that divergence is closed.)
  - **Executable example:** the [implementer cookbook](../guides/implementer-guide.md#extend-the-free-application) owns build-time installation and behavior/safety proof.
- **`JournalEnricher` SPI** — not part of selection itself, but adjacent: enriches `InventoryJournal` audit entries (every reserve/transfer that *follows* a selection) with custom fields.
- **Inactive-product projection** — extension-relevant indirectly: any module/extension that changes product lifecycle state must fire `ItemDataStateChangedEvent` for selection to respect it (§2.1).

---

## 8. Code Pointers

| What | Where |
|---|---|
| Algorithm (13-pass loop, pass gating, accumulation, picking type) | `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/StockSelectionService.kt` — `selectStock()`, `getCandidatesForPass()`, `applyFilters()`, `determinePickingType()` |
| Candidate query + FIFO ordering | `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/repository/StockUnitRepository.kt` — `findForSelection()` |
| Request/response contracts | `services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/vo/StockSelectionRequest.kt`, `.../api/vo/PickStockResult.kt`, `StockSelectionResponse` in `.../api/dto/StockUnitDto.kt` |
| In-process reservation caller | `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/DefaultStockReserver.kt` - `reserve()` / `doReserve()` |
| SPI | `.../karyo-inventory-api/.../api/spi/StockSelectionFilter.kt` |
| Inactive-product guard | `.../repository/InactiveProductRepository.kt`; `.../messaging/ProductStateChangedObserver.kt` |
| Tests | `services/karyo-app/src/test/kotlin/com/karyo/inventory/service/StockSelectionServiceTest.kt`; the lot-targeting contract of §3 (`lotNumber` preference vs. the `enforceLot` fence) is in the sibling `StockSelectionLotTest.kt` |
| Behavioral contract | This specification, especially §2-§5; implementation in `StockSelectionService` and `StockUnitRepository.findForSelection()` |

---

## 9. Divergence Register (Karyo vs. myWMS) — the gap tracker

| # | Divergence | Status |
|---|---|---|
| 1 | ~~`preferMatching` (exact-amount preference) accepted but ignored~~ | **Fixed** 2026-06-13 (v1.3 3.1) — exact-amount preference implemented; Example A now selects the exact unit |
| 2 | ~~`completeHandling` modes + combinatorial Optimizer (incl. "complete or nothing" early exit) absent~~ | **Fixed** 2026-06-13 (v1.3 3.1) — all 5 modes + bounded `Optimizer` + complete-or-nothing |
| 3 | Specialized location-based passes and packaging-unit rounding absent; fixed-slot `maxPickAmount` ceiling present | Partial gap - the ceiling is enforced, while area and packaging rules remain absent |
| 4 | Strict complete-handling candidate validation now **gates candidacy** in the completeHandling passes (unopened, unreserved, single-stock, COMPLETE type) — the **STORAGE-area / not-on-fixed-location** checks remain deferred | Partially fixed (v1.3 3.1); area/fixed checks → **Spec B** |
| 5 | myWMS pass 10 (FIFO-preserving complete pick when `preferComplete=false`) absent | Gap |
| 6 | Lot is preference-with-fallback by default; **strict lot now available via `enforceLot`** | Resolved (v1.3 3.1) — `enforceLot` is the strict-lot flag; default stays preference-with-fallback (Example C) |
| 7 | Passes are now genuinely distinct on the *strategy* dimensions (strict vs relaxed complete validation, preferMatching, completeHandling) | **Fixed** (v1.3 3.1) for strategy passes; the **location** passes (#3) remain deferred to Spec B |
| 8 | Multi-pick accumulation in one call vs. myWMS one-stock-per-call iteration | Intentional API improvement |
| 9 | ~~SPI filter reordering is discarded (FIFO order always re-imposed)~~ | **Fixed** 2026-06-13 (filter-order correction) — filter order is now honored |
| 10 | Inactive-product exclusion via event-maintained projection | Karyo addition (replaces cross-service lookup) |
| 11 | Partial fulfillment returned with `fullyFulfilled=false` instead of null/failure | Intentional API improvement |
