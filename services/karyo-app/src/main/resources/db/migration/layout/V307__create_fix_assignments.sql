CREATE TABLE IF NOT EXISTS fix_assignments (
    id               BIGSERIAL PRIMARY KEY,
    version          INT NOT NULL DEFAULT 0,
    created          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id        BIGINT NOT NULL,
    location_id      BIGINT NOT NULL REFERENCES storage_locations(id),
    item_data_id     BIGINT NOT NULL,
    item_data_number VARCHAR(100),
    min_amount       NUMERIC(17,4),
    max_amount       NUMERIC(17,4),
    desired_amount   NUMERIC(17,4),
    order_index      INT DEFAULT 0,
    UNIQUE(location_id, item_data_id)
);

CREATE INDEX idx_fix_assignments_client ON fix_assignments(client_id);
CREATE INDEX idx_fix_assignments_item ON fix_assignments(item_data_id);
CREATE INDEX idx_fix_assignments_location ON fix_assignments(location_id);
