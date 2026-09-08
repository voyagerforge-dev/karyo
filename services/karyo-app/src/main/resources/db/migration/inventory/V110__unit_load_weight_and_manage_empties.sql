-- Row 16. manageEmpties: when true, an emptied unit load of this type is KEPT rather than
-- auto-trashed. Public behavioral contract:
-- docs/functional/inventory-operations.md#4-empty-unit-load-lifecycle.
ALTER TABLE karyo.unit_load_types ADD COLUMN manage_empties BOOLEAN NOT NULL DEFAULT FALSE;

-- Row 16. Weight inputs on the unit load. weight_calculated is the recomputed value
-- (type tare plus the weight of everything on it); weight_measure is a manual override, for
-- instance a scale reading. The existing weight column stays and holds the effective value,
-- weight_measure when set, otherwise weight_calculated: it is read by JPQL in
-- StockUnitRepository.findOnStockUnitLoadWeightByLocationIds, so it cannot become transient.
ALTER TABLE karyo.unit_loads ADD COLUMN weight_calculated NUMERIC(19,3);
ALTER TABLE karyo.unit_loads ADD COLUMN weight_measure NUMERIC(19,3);
