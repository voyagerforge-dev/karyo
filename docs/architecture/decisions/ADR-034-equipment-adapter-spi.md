# ADR-034: Equipment Adapter SPI for Vendor WCS Integration

> **MECHANISM TRANSLATED post-pivot** — see [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
> The **design below is still plan of record**; this feature is unbuilt.
>
> The `EquipmentAdapter` SPI stands unchanged — it faces *external* vendor equipment (WCS/AMR/
> conveyor), which is genuinely remote and unaffected by the pivot. Only Karyo-internal plumbing
> changes: adapter events are CDI events, not Kafka topics.
>
> The body below records the original decision context. The post-pivot mechanism above is the
> proposed mechanism only. `EquipmentAdapter` has no current Kotlin declaration or shipped
> vendor execution path. Ordinary human transport tasks are implemented; this ADR does not
> describe their present API. See the [ADR index](README.md).


## Status
Accepted

## Context
Karyo WMS inherits from myWMS, which was designed for manual warehouses — all transport tasks are assigned to human operators who confirm completion via handheld scanners. However, modern warehouses increasingly use automated equipment: conveyor systems, Autonomous Mobile Robots (AMRs), Automated Storage and Retrieval Systems (AS/RS), and shuttle systems. These are controlled by vendor-provided Warehouse Control Systems (WCS).

**Key insight:** Karyo should not build a WCS. Vendors like AutoStore, Dematic, Locus Robotics, and Honeywell Intelligrated provide mature WCS systems that directly control PLCs, conveyors, and robots. Karyo's role is to **delegate transport execution** to these vendor systems and **receive completion confirmations** back — the same logical flow as human operator execution, but with a machine on the other end.

**Design constraints:**
- The human operator flow must remain the default and must not be affected by equipment adapter infrastructure
- Adding support for a new vendor must not require changes to core task-service code
- Downstream consumers (inventory-service, layout-service) must receive identical events regardless of whether a human or robot executed the transport
- The `plcCode` field already exists on `StorageLocation` (inherited from myWMS) and provides the address bridge between Karyo locations and equipment systems

## Decision
We will implement equipment integration as an **SPI (Service Provider Interface) layer** within the task-service, following the same extension pattern used throughout Karyo (see [extensibility architecture](../extensibility-architecture.md)).

### Three SPI Interfaces (in `karyo-task-api`)

1. **`EquipmentAdapter`** — Translates Karyo transport commands to vendor WCS API calls. Methods: `dispatchTransport()`, `cancelTransport()`, `queryStatus()`, `healthCheck()`.

2. **`EquipmentAdapterFactory`** — CDI discovers all factories at startup. Each factory declares which `ExecutorType` + `vendorCode` combination it supports. Core calls `create(channel)` to instantiate the right adapter for each `EquipmentChannel`.

3. **`TaskExecutorRouter`** — Determines per-transport whether execution goes to a human operator or to equipment. Default implementation checks if the source/destination area has an active `EquipmentChannel`. Clients override with `@Alternative @Priority`.

### Data Model

- **`EquipmentChannel`** (new entity): Configured connection to a vendor WCS — name, `ExecutorType` (HUMAN/CONVEYOR/AMR/AS_RS/SHUTTLE), `vendorCode`, JSONB config, health status, linked area.
- **`TransportOrder`** (3 new fields): `executorType` (default HUMAN), `equipmentChannelId`, `equipmentOrderId` (external ID from vendor WCS).

### Execution Flow

```
TransportOrder created
    → TaskExecutorRouter.resolveExecutor()
        → HUMAN: existing operator flow (unchanged)
        → Equipment: EquipmentAdapterFactory.create(channel)
            → adapter.dispatchTransport(command)
            → Store equipmentOrderId, set state = STARTED
            → Vendor WCS executes physical movement
            → Vendor calls POST /api/internal/transport-orders/{id}/equipment-confirm
            → Same downstream: inventory transfer + event publish
```

### Vendor Adapter JAR Pattern

```
services/task-service/
├── karyo-task-api/              ← SPI interfaces
├── karyo-task-core/             ← Default router, dispatch service
├── karyo-task-autostore-ext/    ← AutoStore WCS adapter (Phase 3)
├── karyo-task-dematic-ext/      ← Dematic iQ adapter (future)
└── karyo-task-locus-ext/        ← Locus Robotics adapter (future)
```

Each vendor adapter JAR compiles against `karyo-task-api` only, never against core.

## Consequences

### Positive
- **Zero core changes per vendor:** New equipment support = new extension JAR on classpath
- **Human flow untouched:** Default `TaskExecutorRouter` returns HUMAN when no equipment channel exists — existing operator workflows are completely unaffected
- **Unified downstream events:** `transport-order.completed` fires identically for human and equipment execution — inventory-service, layout-service, and reporting-service don't need to know about automation
- **Graceful degradation:** If equipment dispatch is rejected (WCS down, queue full), automatic fallback to HUMAN executor
- **Consistent with Karyo patterns:** Same SPI + CDI discovery + extension JAR pattern as cross-docking (CrossDockingMatcher), stock selection (StockSelectionCustomizer), and ERP integration (ErpAdapter)

### Negative
- **Vendor API complexity:** Each vendor WCS has a different API contract. The SPI must be general enough to accommodate all while being specific enough to be useful
- **Callback reliability:** Equipment confirmation depends on the vendor WCS calling back. Timeout handling and manual intervention flows add complexity
- **Health monitoring overhead:** `@Scheduled` health check for each active channel adds periodic load

### Neutral
- Equipment adapter infrastructure ships in Phase 2 (alongside task-service), but no vendor adapters until Phase 3. The SPI interfaces and routing logic exist but the default router always returns HUMAN until a vendor adapter JAR is deployed
- `plcCode` on StorageLocation becomes the address bridge between Karyo locations and equipment systems. Warehouses with automation must populate this field

## Alternatives Considered

### 1. Build a WCS (Direct PLC/Equipment Control)
Karyo would directly communicate with PLCs via OPC-UA, MQTT, or raw TCP (like OpenWMS.org's OSIP driver). This would roughly double the project scope, require real-time communication expertise, and compete with mature vendor WCS systems that already handle equipment control. **Rejected** — Karyo is a WMS, not a WCS.

### 2. Separate Equipment Execution Service
A new `equipment-execution-service` microservice dedicated to equipment orchestration. This would add a new service boundary, new database, and cross-service communication complexity for what is fundamentally an extension of task-service's transport order lifecycle. **Rejected** — unnecessary service boundary; the SPI pattern keeps equipment logic in the same bounded context.

### 3. Integration Hub Adapters
Route equipment commands through integration-hub-service like ERP/carrier adapters. While the adapter pattern is similar, equipment execution is tightly coupled to the transport order lifecycle (state transitions, confirmation handling, fallback to human) — routing through another service would add latency and transactional complexity. **Rejected** — equipment is a task execution concern, not an integration concern.

## Implementation Notes

- **Phasing:** Phase 2 = SPI interfaces + infrastructure + health monitor (default router returns HUMAN). Phase 3 = first vendor adapter. Phase 4+ = optional commodity PLC driver (if market demand).
- **plcCode field:** Already on StorageLocation in warehouse-layout-service. This is the address bridge — `TransportCommand.sourceLocationPlcCode` and `destinationLocationPlcCode` are populated from the location's `plcCode`.
- **Area linkage:** `EquipmentChannel.areaId` links to warehouse-layout-service `Area.id`. The `Area` entity gains an `equipmentChannelId` field for bidirectional lookup. `TaskExecutorRouter` uses this to determine if a transport's source/destination area has automated equipment.
- **Callback endpoint:** `POST /api/internal/transport-orders/{id}/equipment-confirm` is idempotent. The vendor WCS must include the `equipmentOrderId` for correlation.
- **Health monitoring:** `@Scheduled(every = "60s")` iterates active channels, calls `adapter.healthCheck()`, publishes `karyo.tasks.equipment.health-changed` on status transitions.

## Related Decisions
- [ADR-001](superseded/ADR-001-microservices-architecture.md) — Microservices architecture (task-service bounded context)
- [ADR-008](ADR-008-rest-sync-events-async.md) — REST for sync, Kafka for async (equipment callback is sync REST)
- [ADR-010](ADR-010-choreography-sagas.md) — Choreography sagas (equipment confirmation triggers same saga events)
- [ADR-033](ADR-033-cross-docking-event-interceptor.md) — Cross-docking SPI pattern (same extension mechanism)
- [Extensibility Architecture](../extensibility-architecture.md) - public SPI and customer-extension delivery model
