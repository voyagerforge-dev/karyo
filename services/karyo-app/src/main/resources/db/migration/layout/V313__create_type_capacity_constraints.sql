-- L2 (locations-layout sprint, Task 4): myWMS TypeCapacityConstraint — the (LocationType,
-- UnitLoadType) compatibility/capacity matrix. Replaces the finder's permissive filter-7
-- UL-type stub (`unitLoadTypeCompatible()`, always true). `unit_load_type_id` is a FOREIGN
-- MODULE id (inventory) — deliberately NO FK; inventory-api exposes no UnitLoadType lookup
-- SPI today, so this id is accepted unvalidated at the service layer (advisory integrity,
-- consistent with the project's no-cross-module-FK rule — see TypeCapacityConstraintService).
CREATE TABLE IF NOT EXISTS type_capacity_constraints (
    id                BIGSERIAL PRIMARY KEY,
    version           INT NOT NULL DEFAULT 0,
    created           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    location_type_id  BIGINT NOT NULL REFERENCES location_types(id),
    unit_load_type_id BIGINT NOT NULL,
    allocation        NUMERIC(5,2) NOT NULL DEFAULT 100,
    order_index       INT NOT NULL DEFAULT 0,
    UNIQUE(location_type_id, unit_load_type_id)
);

CREATE INDEX idx_type_capacity_constraints_location_type ON type_capacity_constraints(location_type_id);
