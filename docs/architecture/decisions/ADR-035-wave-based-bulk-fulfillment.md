# ADR-035: Wave-Based Bulk Fulfillment with Hybrid Picking

> **MECHANISM TRANSLATED post-pivot** — see [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
> **Current disposition (2026-09-08): implemented commercial orchestration.** The original
> design body is history. Later work also implemented selection rules, sorting, bulk confirmation
> and cross-order pack-out. The note below records the first shipped increment, not today's
> full capability boundary. Consult current API contracts rather than the original phase plan.
>
> The wave lifecycle and hybrid-picking design stand. The saga with compensation steps is
> unnecessary — a wave release is a single in-process transaction — and wave-state notification is a
> CDI event rather than a Kafka topic.
>
> The body below records the original decision context. The post-pivot mechanism above is the
> post-pivot direction, qualified by the implementation notes here.
>
> **Status update 2026-08-21: Phase A SHIPPED.** `services/wave-service/karyo-wave-{api,core}`,
> license key `advanced-fulfillment` (second Advanced Fulfillment pack member after cross-docking).
> One deviation from the design below: the **`ALLOCATING` state is dropped** from the lifecycle
> (`PLANNED(100) -> RELEASED(300) -> PICKING(400) -> CONSOLIDATING(500) -> COMPLETED(700)`,
> `CANCELLED(900)` from any pre-COMPLETED state) -- under the modular monolith a wave release
> (allocation + reservation + pick generation) is one in-process `@Transactional` operation, so
> `ALLOCATING` could never be observed from outside it. Everything else below (wave/
> ConsolidationGroup entities, `AllocationCustomizer`/`ConsolidationCustomizer` SPIs, hybrid
> COMPLETE/PICK split, `AllocationShortageAction`, priority-based allocation) shipped as designed.
> This status block is the public as-shipped summary; the wave API contracts and runtime behavior
> are authoritative for the implementation. **Phase B**
> (full UoM hierarchy) and **Phase C** (pack optimization, fair-share allocation example, AI wave
> sizing) remain open follow-ups, tracked on the B2 row notes.


## Status
Accepted

## Context
Karyo WMS needs bulk fulfillment capabilities for two distribution models: DC-to-store retail replenishment and B2B wholesale distribution. myWMS has no wave picking, batch picking, multi-order picking, or store allocation functionality — it processes orders individually.

Modern warehouse operations require the ability to group multiple orders into waves, allocate inventory across orders with priority-based logic, generate batch picks for efficiency, and consolidate items by destination for shipment. Without this, Karyo cannot serve retail distribution centers or high-volume B2B warehouses.

**Existing building blocks in Karyo/myWMS:**
- 13-pass FIFO `PickingStockFinder` for stock selection
- 6-mode COMPLETE optimizer (`OrderStrategy.completeHandling`)
- `PickingType.PICK` vs `PickingType.COMPLETE` classification
- `OrderStrategy.createTypeOrders` flag for PICK/COMPLETE splitting
- `PickOrderGroupingStrategy` SPI (designed, not implemented)
- `OrderStrategy.extensionProperties` JSONB for per-strategy configuration

**Missing capabilities:**
- Wave entity and lifecycle management
- Priority-based stock allocation across multiple orders
- Batch picking (multi-order cart picks grouped by zone)
- Configurable wave pick modes (HYBRID, COMPLETE_ONLY, PICK_ONLY)
- Consolidation workflow (merge-by-destination after picking)
- Wave scheduling (manual and automated time-window release)

## Decision
We will implement wave-based bulk fulfillment using a **phased build approach** with a hybrid picking model.

### Phase A: Wave Entity + Hybrid Pick Generation (order-service only)
- **Wave entity** with lifecycle states (PLANNED → ALLOCATING → RELEASED → PICKING → CONSOLIDATING → COMPLETED)
- **Hybrid pick generation** that splits allocated lines into COMPLETE (full case/pallet, direct from reserve) and PICK (eaches, batch-grouped by zone) paths
- **wavePickMode** configurable per OrderStrategy: `HYBRID`, `COMPLETE_ONLY`, `PICK_ONLY`
- **Priority-based allocation** (DeliveryOrder.prio DESC, created ASC) with `AllocationCustomizer` SPI for client overrides
- **ConsolidationGroup** entity tracking merge-by-destination progress
- **Wave scheduling** via cron-based auto-release or manual wave creation
- Phase A operates entirely in eaches (base UoM) — no UoM hierarchy required

### Phase B: Full UoM Hierarchy (product + inventory + order services)
- `ItemDataUom` entity in product-service (UoM conversion table per product)
- `StockUnit.uom` field in inventory-service with UoM-aware stock selection
- UoM-aware wave allocation in order-service

### Phase C: Advanced Extensions
- Pack optimization SPI (`ConsolidationCustomizer`)
- Fair-share allocation example extension
- AI-assisted wave sizing

### Key Design Decisions

**wavePickMode (HYBRID / COMPLETE_ONLY / PICK_ONLY):**
- `COMPLETE_ONLY` enables fast DC-to-store fulfillment — stores accept case-level rounding, no eaches batch picking needed
- `HYBRID` enables B2B fulfillment — every unit must ship, eaches remainder goes through batch picking
- `PICK_ONLY` for small-parcel/e-commerce (future)
- A DC can run COMPLETE_ONLY waves every 2 hours for speed, then a HYBRID wave at end-of-day for remainders

**Allocation is an SPI extension point:**
Core implements priority-based allocation (first-come, first-served within priority tiers). The `AllocationCustomizer` SPI is wide open for client overrides — fair-share, zone-based, custom business rules. Allocation logic is highly business-specific.

**Backward compatibility:**
`DeliveryOrder.waveId` is nullable. Orders not in a wave work exactly as they do today. The wave feature is opt-in via OrderStrategy configuration.

## Consequences

### Positive
- **Retail DC-to-store fulfillment:** COMPLETE_ONLY waves enable high-speed case-level distribution
- **B2B wholesale:** HYBRID waves handle mixed UoM fulfillment with batch picking efficiency
- **Configurable per strategy:** Different OrderStrategies can use different wave modes, triggers, and allocation logic
- **Phased delivery:** Phase A delivers value (wave picking in eaches) without waiting for UoM hierarchy
- **Extension-friendly:** AllocationCustomizer and ConsolidationCustomizer SPIs follow Karyo's established extensibility pattern
- **Backward compatible:** Existing non-wave order processing is unaffected

### Negative
- **order-service complexity increase:** Wave lifecycle, allocation, consolidation add significant business logic to the already largest service
- **Phase B coupling:** Full UoM hierarchy touches three services (product, inventory, order) simultaneously
- **Wave + non-wave coexistence:** Must handle mixed scenarios where some orders use waves and others don't

### Neutral
- Wave entity and infrastructure ship in Phase 2 (alongside order-service). UoM hierarchy in Phase 3.
- ConsolidationGroup is a lightweight tracking entity — it doesn't manage physical container building

## Alternatives Considered

### 1. Strategy-Driven Extension Only (No Wave Entity)
Leverage existing `PickOrderGroupingStrategy` SPI with batch metadata on PickingOrder. No new Wave entity. **Rejected** — wave lifecycle tracking (progress, allocation state, completion) is critical for operational visibility. A `batchId` field can't provide "wave 42 is 60% picked" metrics.

### 2. Full Build (Everything at Once)
Build Wave + UoM + allocation + consolidation in one phase. **Rejected** — wave orchestration and UoM hierarchy are independent concerns. You can batch-pick 100 orders by zone without knowing whether a "case" is 12 or 24 units. Building separately reduces risk and delivers value faster.

### 3. Separate Wave Planning Service
Extract wave management into its own microservice. **Rejected** — waves are tightly coupled to order allocation and pick generation. Cross-service transactions for allocation would add latency and complexity. The wave lifecycle is a natural extension of the order-service bounded context.

## Implementation Notes

- **DeliveryOrder** gains nullable `waveId` FK. **PickingOrder** gains `waveId`, `pickingType`, `batchZone` fields.
- **OrderStrategy wave config** stored in `extensionProperties` initially: `waveAutoRelease`, `waveCronSchedule`, `waveMaxOrders`, `wavePickMode`, `waveCarrierCutoffMinutes`. Promote to typed fields if usage patterns stabilize.
- **AllocationShortageAction** enum: `SKIP`, `SHORT_ALLOCATE`, `HOLD_ORDER`, `HOLD_WAVE` — controls behavior when stock is insufficient during wave allocation.
- **ConsolidationGroup** tracks merge-by-destination with states `PENDING → IN_PROGRESS → READY → SHIPPED`.
- **Kafka events:** `karyo.orders.wave.state-changed`, `karyo.orders.wave.allocation-completed`, `karyo.orders.wave.pick-progress`.
- **REST endpoints:** 6 new wave endpoints (CRUD + release + cancel + progress).

## Related Decisions
- [ADR-010](ADR-010-choreography-sagas.md) — Choreography sagas (wave allocation triggers same event-driven flows)
- [ADR-033](ADR-033-cross-docking-event-interceptor.md) — Cross-docking SPI pattern (same extensibility approach for AllocationCustomizer)
- [ADR-034](ADR-034-equipment-adapter-spi.md) — Equipment adapter (COMPLETE picks can be dispatched to AS/RS via equipment adapter)
- [Extensibility Architecture](../extensibility-architecture.md) - public strategy and SPI contracts
