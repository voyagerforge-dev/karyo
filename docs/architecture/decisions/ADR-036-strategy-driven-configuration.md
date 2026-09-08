# ADR-036: Strategy-Driven Configuration

> **Implementation context (2026-09-08):** the three-tier intent remains. Selection differs
> by SPI: filter chains, first-non-null strategies, named/keyed resolvers and direct CDI injection
> coexist. A universal priority/first-non-null rule would misdescribe real interfaces such as
> `WebhookSigner`. See [extensibility](../extensibility-architecture.md) before writing an extension.

## Status
Accepted

## Context

Karyo WMS must be **light and easy to use** (an operator should configure nothing and have it
work) while offering **real configuration flexibility** for a final, full-functionality product:
choose a picking strategy per order (and later per order-type / per wave), configure short-pick
handling, tune selection behavior, swap putaway algorithms, etc. The solo-dev brief is explicit
that there is no demo deadline — the target is a configurable product, not an MVP with one baked-in
flow.

A configuration model has **already emerged organically** in the codebase but was never named or
formalized as the house standard:

- **Two named-config strategy entities** on a common template: `OrderStrategy` (`order_strategies`
  — `name`, `useLockedStock`, `preferComplete`, `extensionProperties` JSONB) and `StorageStrategy`
  (`storage_strategies` — `name`, `zoneId`, `mixItem`, `mixClient`, `nearPickingLocation`, `sorts`).
- **Three SPI seams** across two modules: `StockSelectionFilter` (prune/veto pick candidates),
  `LocationFilter` (prune/reorder putaway candidates), and `PutawayLocationStrategy` (fully replace
  the putaway finder) — the last with a well-formed pattern: priority-ordered beans, first-non-null
  wins, the built-in registered at lowest priority so any custom strategy pre-empts it, "return null
  = no opinion, fall through."
- **Per-operation binding + resolution** already wired: `DeliveryOrder.orderStrategyId` (ID-only,
  `null = DEFAULT`), resolved via `OrderStrategyService.resolveEntity(id)` with a `DEFAULT` fallback.
- The **`extensionProperties` JSONB relief valve** on strategies for config that isn't a typed column.

Prior ADRs already assume this model: **ADR-035** references `OrderStrategy.completeHandling`,
`OrderStrategy.wavePickMode`, `OrderStrategy.createTypeOrders`, and a designed-but-unbuilt
`PickOrderGroupingStrategy` SPI; **ADR-034** (Equipment Adapter SPI) is, in effect, a putaway/pick
strategy for WCS-driven placement; the **extensibility-architecture** doc names SPI / CDI-hook /
Strategy-JSONB / webhook as the four extension mechanisms.

**Problem:** without a formal contract, each new module (fulfillment, ai, reporting, …) is free to
invent its own configuration approach, knobs get hardcoded as the "only" behavior, and adding
flexibility later means retrofitting entities. The bones exist; they need to be named, generalized,
completed, and made mandatory.

## Decision

Adopt **Strategy-Driven Configuration** as the house standard for all tunable behavior. Five
elements:

### 1. Named, selectable strategy-config entities per domain operation

Each domain operation with meaningful knobs owns a **named strategy entity** following a common
template: unique `name`, a seeded `DEFAULT`, typed knob fields, and an `extensionProperties` JSONB
column. Existing: `OrderStrategy` (outbound/picking), `StorageStrategy` (putaway). Future modules add
their own (e.g. a packing/shipping strategy) rather than a single god-config object.

**Scoping is per-strategy-type's choice, system-level by default.** Under silo tenancy most strategies
are system-level (no `clientId`, like `OrderStrategy`). A strategy type **may** extend `TenantEntity`
when per-goods-owner configuration is genuinely meaningful — `StorageStrategy` does this so a 3PL can
run different putaway rules per goods owner. This is a deliberate capability, not an inconsistency to
"fix"; the rule is: default to system-level, opt into `TenantEntity` only when per-client config has a
real use case.

### 2. Three-tier knob governance

| Tier | Mechanism | Use when |
|---|---|---|
| 1 — Typed field | Enum/scalar column on the strategy entity | The knob is stable and common across deployments |
| 2 — JSONB | `extensionProperties` on the strategy | Long-tail / customer-specific / experimental config |
| 3 — SPI bean | A discovered CDI bean (Filter or Strategy) | New *behavior*, not just a value |

**Graduation rule:** a knob starts in JSONB (tier 2) when experimental; it graduates to a typed field
(tier 1) once it is stable and used across customers. Behavior that can't be expressed as a value is a
tier-3 SPI. Every new knob must be consciously placed in a tier (reviewed at plan time).

### 3. The Strategy-SPI convention (generalized from `PutawayLocationStrategy`)

All pluggable-behavior SPIs follow one shape:

- **Filters** prune/veto/reorder a built-in's candidate set (`StockSelectionFilter`, `LocationFilter`):
  priority-ordered, each returns the subset/order to keep.
- **Strategies** fully replace a built-in algorithm (`PutawayLocationStrategy`,
  `PickOrderGroupingStrategy`, `ShortfallStrategy`, `PickDifferenceStrategy`, `CarrierAdapter`,
  ADR-034 `EquipmentAdapter`): priority-ordered beans, **first non-null wins**, the **built-in
  registers at the lowest priority** (`Int.MAX_VALUE`, runs last), and **returning null means "no
  opinion, fall through."** Strategies that answer a named knob (e.g. `OrderStrategy.shortfallStrategy`,
  `pickDifferenceStrategy`, `pickOrderGrouping`) carry a `name` and the resolver prefers the
  name-matching bean before falling through the priority chain.

This is THE pattern for tier-3 extension across modules. (Note: ADR-035's `PickOrderGroupingStrategy`
and this milestone's pick-grouping seam are the **same** SPI — v1.3 ships the `DISCRETE` grouping; wave
grouping plugs into the same seam later.)

**Short-pick seams (v1.3 sub-phase 3.2b-2).** Short-pick handling registers two Strategy-SPI seams,
each shipping only its lightest built-in with heavier behaviours deferred to future beans:
- **`ShortfallStrategy`** (`fulfillment`) — the uncovered remainder of a short pick. Built-in
  `PartialShipShortfallStrategy` (`PARTIAL_SHIP`: report + accept short). Future: `PENDING_ESCALATION`,
  `AUTO_RECOVERY`. Selected by `OrderStrategy.shortfallStrategy`.
- **`PickDifferenceStrategy`** (`fulfillment`) — the short *source's* residual. Built-in
  `LeaveDifferenceStrategy` (`LEAVE`: leave the stock, exclude the source from re-selection). Future:
  `WRITE_OFF`, `QUARANTINE`. Selected by `OrderStrategy.pickDifferenceStrategy`.

The cover-attempt order is a plain typed enum knob (`OrderStrategy.shortPickMode`), not an SPI — it
selects *whether* to follow-up and/or substitute, a closed set with no pluggable behaviour.

### 4. Binding via a `StrategyResolver` seam

How an operation acquires its strategy is **behind a resolver SPI**, so binding can evolve without
entity churn:

- **Today (direct reference):** the operation entity carries a `strategyId` (`DeliveryOrder.orderStrategyId`);
  the default resolver returns `resolveEntity(strategyId)` or the seeded `DEFAULT`.
- **Later (attribute/rule-based):** a resolver implementation maps operation *context* (order-type,
  client, priority, wave) → strategy via a rules table. This plugs into the same seam when order-type
  and waves exist (v2.x) — **the resolver takes the whole operation context as input, so order-type can
  be added as a resolver input with no change to the strategy entities.**

Building the per-attribute rules engine now is explicitly **out of scope** (YAGNI — no order-type/wave
consumers yet); only the seam + the trivial direct-reference default are built now.

### 5. Management surface — Strategies admin UI

A **Strategies admin screen** (CRUD named strategies, edit typed knobs + a JSONB editor for tier-2)
ships so the flexibility is **operator-usable**, not API-only. The existing REST resources
(`OrderStrategyResource`, `StorageStrategyResource`) back it.

## Consequences

**Positive**
- **Light by default, flexible on demand:** the seeded `DEFAULT` strategy means zero configuration to
  start; named strategies + JSONB + SPIs provide depth only when needed.
- **Cross-cutting consistency:** every current and future module (fulfillment, ai, reporting) follows
  one configuration contract instead of N ad-hoc approaches.
- **Aligns existing work:** ADR-034's equipment adapter, ADR-035's wave config + grouping SPI, and the
  extensibility-architecture mechanisms are all instances of this model.
- **Extension-friendly:** customers can tune via data (strategies/JSONB) or code (SPI JARs compiled
  against `api` only) without forking core.

**Negative / costs**
- **Discipline cost:** every new knob must be classified into a tier at plan time; resolver/SPI
  indirection adds a little ceremony over a hardcoded value.
- **Indirection:** reading behavior now means "resolve strategy → read knob," not a constant.

## Implementation (first slice — v1.3 sub-phase 3.0)

- `StrategyResolver` SPI + trivial direct-reference default impl (orders module; generalizable).
- **Strategies admin UI** (CRUD `OrderStrategy` / `StorageStrategy`, typed knobs + JSONB editor).
- This ADR + a short governance note in `extensibility-architecture.md`.
- v1.3 picking knobs land as **tier-1 fields on `OrderStrategy`**: `completeHandling`, `preferMatching`,
  `enforceLot`, `shortPickMode`, `pickOrderGrouping` (the grouping-SPI selector).
- Each subsequent v1.3 sub-phase (and every future milestone) expresses behavior as **default + knob**
  per this ADR.

## Alternatives Considered

- **Single unified config object** — rejected: unwieldy; domain-specific strategies group related knobs
  and bind to different operations.
- **Pure code/SPI config (no data-driven strategies)** — rejected: operators couldn't tune without a
  redeploy; not product-grade.
- **Full attribute-based rules engine now** — rejected as premature (YAGNI): no order-type or wave
  consumers exist yet. Design the resolver seam, defer the rules.
- **Defer all configuration to a later milestone** — rejected: retrofitting config onto entities built
  without it is costly, and the bones already exist — formalizing now is cheap.

## Related

- **[Extensibility Architecture](../extensibility-architecture.md)** - the public Strategy-SPI
  contract and customer-extension delivery model.
- Extends the **extensibility-architecture** (SPI / CDI-hook / Strategy-JSONB / webhook).
- Complements **ADR-034** (Equipment Adapter SPI) and **ADR-035** (wave config + `PickOrderGroupingStrategy`)
  — both are instances of §3.
- Consumed first by the outbound stock-selection and picking behavior documented in
  [Stock Selection](../../functional/stock-selection.md) and [Order Picking](../../functional/picking.md).
