# Functional Specification: Inventory Operations

**Module:** Inventory
**Status:** Documents implemented behavior as of 2026-08-30
**Audience:** Operators, implementation consultants, support engineers, and extension authors
**Source of truth:** `karyo-inventory-{api,core}` and the cross-module reservation-reference implementations

## 1. Goods ownership and scope

A `client` is the goods owner inside one Karyo deployment. Stock units and unit loads carry that
owner as `client_id`; client id `0` is the system client. The `clients` table deliberately has no
cross-module foreign keys. `GET /api/v1/clients/consistency` reports references to missing clients
instead of relying on database constraints across module boundaries.

Inventory writes are owner-scoped. Cross-owner movement is refused even for an operations
principal with unscoped access. Ownership changes use the separately guarded change-client path.

## 2. Atomic stock mutations

### Packaging-unit reclassification

`POST /api/v1/stock-units/{id}/change-packaging-unit` changes only the stock unit's
`packagingUnitId`. It does not convert or change `amount`. A non-null packaging unit must exist and
belong to the same item, and stock at or beyond `PICKED(600)` cannot be reclassified. A successful
change writes the inventory journal and a `PackagingUnitChanged` outbox event.

### Reservation transfer

`POST /api/v1/stock-units/{id}/transfer-reservation` atomically moves a requested reserved amount
to another stock unit and repoints the matching order-line and open-pick references in the same
transaction. A null amount means the source's entire current reservation. Every refusal is checked
before the first write.

The source and target ids must differ. Both rows must be visible to the caller's write scope and
must have the same owner and item. Lot number and best-before date are not equality guards on this
operation. The amount must be positive and no greater than the source reservation. The target must
be unlocked, `ON_STOCK(300)`, and have `amount - reservedAmount` at least as large as the transfer.
A same-id request is rejected as invalid input; cross-owner, insufficient-reservation,
insufficient-target-availability, locked-target, and live-reference conflicts are conflicts; and a
wrong-item or non-ON_STOCK target is an invalid target. Refused requests leave both stock units and
all references unchanged.

A partial transfer is refused while a live order-line or open-pick reference remains. Reservation
history can also contain terminal-consumed and still-live portions of the same allocation slice.
For such mixed history, the requested amount must equal the total live remainder across every
reference mover. Karyo leaves the terminal portion attached to the stock that actually supplied it
and moves the live remainder by splitting or repointing reference rows per slice. This keeps order
cancellation's terminal-pick netting correct.

## 3. Unit-load weight

A unit load's calculated weight is recomputed in full at each supported mutation point:

`unit-load-type tare + sum(stock amount x item weight)`

Only stock that is still physically on the unit load contributes. `SHIPPED(680)` and
`DELETABLE(1000)` stock is excluded. An item with no recorded weight contributes zero, so one
unknown measure does not hide the known part of the load.

`weightCalculated` stores the formula result. A manual `weightMeasure`, such as a scale reading,
wins when present, and the effective value is stored in `weight`. Full recomputation and the
batched multi-unit-load variant avoid incremental drift while keeping product-measure reads
bounded by client count rather than stock-row count.

## 4. Empty unit-load lifecycle

After a mutation drains a unit load, `UnitLoadTerminator` checks all of its stock without tenant
filtering because physical emptiness is owner-independent. If no stock remains outside
`SHIPPED(680)` and `DELETABLE(1000)`, the normal behavior is to mark the unit load
`DELETABLE(1000)` and emit its journal, outbox, and CDI notifications exactly once.

`UnitLoadType.manageEmpties = true` preserves an emptied reusable container in circulation instead.
The exception applies only to an ordinary emptying operation. A unit load that leaves the building
through shipping still becomes terminal regardless of `manageEmpties`.

## 5. Stock lifecycle and purge

The inventory lifecycle is:

`UNDEFINED(0) -> INCOMING(100) -> ON_STOCK(300) -> PICKED(600) -> PACKED(650) -> SHIPPED(680) -> DELETABLE(1000)`

Shipping promotes stock through `SHIPPED` to `DELETABLE` in the dispatch transaction. The
shipment remains available in the immutable journal while the stock row becomes eligible for
physical cleanup.

Physical purge is disabled by default (`KARYO_INVENTORY_PURGE=false`). When enabled,
`StockPurgeService` selects bounded batches older than the per-client retention window, floors that
window at one day, subtracts every registered `PurgeBlockerLookup` result, then deletes each
surviving stock unit in its own transaction. Empty terminal unit loads are handled after stock and
with the same blocker and retry discipline. One poison row therefore blocks only itself, not the
rest of a tenant's purge tick.

## Code pointers

- `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/StockService.kt`
- `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/ReservationTransferService.kt`
- `services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/spi/ReservationRefMover.kt`
- `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/UnitLoadWeightCalculator.kt`
- `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/UnitLoadTerminator.kt`
- `services/inventory-service/karyo-inventory-core/src/main/kotlin/com/karyo/inventory/service/StockPurgeService.kt`
