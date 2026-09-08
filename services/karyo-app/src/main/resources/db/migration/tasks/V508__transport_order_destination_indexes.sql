-- Defect-burndown-5 (rows :1614, :1627): TransportOrderRepository.findOpenDemandByDestinationIds
-- now matches EITHER destination_location_id (MOVE/REPLENISH, stamped at creation) OR, when
-- that column is null, suggested_location_id (open PUTAWAY/TRANSFER -- destination is stamped
-- only at completion). Both columns are read by the location finder's mixing passes on every
-- putaway/move resolution, hence an index on each.
CREATE INDEX idx_transport_orders_destination ON karyo.transport_orders (destination_location_id);
CREATE INDEX idx_transport_orders_suggested ON karyo.transport_orders (suggested_location_id);
