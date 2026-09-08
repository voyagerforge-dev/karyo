CREATE TABLE IF NOT EXISTS storage_locations (
    id                  BIGSERIAL PRIMARY KEY,
    version             INT NOT NULL DEFAULT 0,
    created             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id           BIGINT NOT NULL,
    name                VARCHAR(100) NOT NULL UNIQUE,
    scan_code           VARCHAR(100),
    location_type_id    BIGINT NOT NULL REFERENCES location_types(id),
    area_id             BIGINT NOT NULL REFERENCES areas(id),
    location_cluster_id BIGINT REFERENCES location_clusters(id),
    zone_id             BIGINT REFERENCES zones(id),
    x_pos               INT NOT NULL DEFAULT 0,
    y_pos               INT NOT NULL DEFAULT 0,
    z_pos               INT NOT NULL DEFAULT 0,
    rack                VARCHAR(50),
    field               VARCHAR(50),
    section             VARCHAR(50),
    allocation          NUMERIC(15,2) NOT NULL DEFAULT 0,
    order_index         INT NOT NULL DEFAULT 0,
    lock_type           INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_locations_client ON storage_locations(client_id);
CREATE INDEX idx_locations_name ON storage_locations(name);
CREATE INDEX idx_locations_scan_code ON storage_locations(scan_code);
CREATE INDEX idx_locations_area ON storage_locations(area_id);
CREATE INDEX idx_locations_zone ON storage_locations(zone_id);
CREATE INDEX idx_locations_type ON storage_locations(location_type_id);
CREATE INDEX idx_locations_lock ON storage_locations(lock_type);
CREATE INDEX idx_locations_allocation ON storage_locations(allocation);
CREATE INDEX idx_locations_finder ON storage_locations(zone_id, area_id, lock_type, allocation);
