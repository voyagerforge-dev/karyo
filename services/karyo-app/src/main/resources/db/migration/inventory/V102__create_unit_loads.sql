CREATE TABLE IF NOT EXISTS unit_loads (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    label_id                VARCHAR(255) NOT NULL UNIQUE,
    external_id             VARCHAR(255),
    unit_load_type_id       BIGINT NOT NULL REFERENCES unit_load_types(id),
    storage_location_id     BIGINT NOT NULL,
    storage_location_name   VARCHAR(100) NOT NULL,
    state                   INT NOT NULL DEFAULT 0,
    opened                  BOOLEAN NOT NULL DEFAULT FALSE,
    is_carrier              BOOLEAN NOT NULL DEFAULT FALSE,
    carrier_unit_load_id    BIGINT REFERENCES unit_loads(id),
    weight                  NUMERIC(16,3),
    position_index          INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_unit_loads_client ON unit_loads(client_id);
CREATE INDEX idx_unit_loads_label ON unit_loads(label_id);
CREATE INDEX idx_unit_loads_location ON unit_loads(storage_location_id);
CREATE INDEX idx_unit_loads_state ON unit_loads(state);
CREATE INDEX idx_unit_loads_type ON unit_loads(unit_load_type_id);
