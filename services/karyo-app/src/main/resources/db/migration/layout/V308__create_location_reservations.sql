CREATE TABLE IF NOT EXISTS location_reservations (
    id                 BIGSERIAL PRIMARY KEY,
    version            INT NOT NULL DEFAULT 0,
    created            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    location_id        BIGINT NOT NULL REFERENCES storage_locations(id) ON DELETE CASCADE,
    transport_order_id BIGINT NOT NULL,
    percent            NUMERIC(15,2) NOT NULL DEFAULT 100,
    expires_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_location_reservations_location ON location_reservations(location_id);
CREATE INDEX idx_location_reservations_to ON location_reservations(transport_order_id);
CREATE INDEX idx_location_reservations_expiry ON location_reservations(expires_at);
