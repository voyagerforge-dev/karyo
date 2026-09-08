-- V613: Bulk Allocation Sprint C final review (ruling 11) -- at most ONE live shipment per
-- consolidation group, enforced by the database.
--
-- Two operators opening the pack-out of the same READY group at the same moment could both read
-- "no live shipment" (ShipmentRepository.findOpenByGroupId) and both mint one, splitting the
-- group's containers across two shipments. PackoutService.open now takes a PESSIMISTIC_WRITE lock
-- on the consolidation_groups row before it reaches the port, which serializes the ordinary race
-- into the intended idempotent return of the winner's shipment. This partial unique index is the
-- backstop underneath that lock: a second live row is impossible however it is attempted -- a
-- direct ConsolidationPackPort call, a replayed request, a future caller that forgets the lock.
--
-- state 800 is ShipmentState.CANCELED. A canceled group shipment is history and must never block a
-- fresh pack-out of the same group, so it is excluded from the index -- the same reading of
-- CANCELED that findOpenByGroupId and PackingService.openPacking's duplicate guard already use.
-- Existing data cannot violate this: Sprint C shipped in this same branch, so no deployed database
-- has ever held two live shipments for one group.
CREATE UNIQUE INDEX idx_shipments_group_live ON shipments (consolidation_group_id)
    WHERE consolidation_group_id IS NOT NULL AND state <> 800;
