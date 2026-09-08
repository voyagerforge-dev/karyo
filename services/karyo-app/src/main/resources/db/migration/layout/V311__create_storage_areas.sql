-- L1 (locations-layout sprint, Task 1): the StorageArea trio — myWMS's named-set-of-clusters
-- concept, distinct from the existing `areas` table (usage roles). Foundation for the
-- finder area-restriction work in later tasks of this sprint.
CREATE TABLE IF NOT EXISTS storage_areas (
    id       BIGSERIAL PRIMARY KEY,
    version  INT NOT NULL DEFAULT 0,
    created  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name     VARCHAR(255) NOT NULL UNIQUE
);

-- Pure M2M join: a location is "in" a storage area iff its cluster is a member here.
-- ON DELETE CASCADE both ways — this table has no independent identity/semantics of its own.
CREATE TABLE IF NOT EXISTS storage_area_clusters (
    storage_area_id     BIGINT NOT NULL REFERENCES storage_areas(id) ON DELETE CASCADE,
    location_cluster_id BIGINT NOT NULL REFERENCES location_clusters(id) ON DELETE CASCADE,
    PRIMARY KEY (storage_area_id, location_cluster_id)
);

CREATE INDEX idx_storage_area_clusters_cluster ON storage_area_clusters(location_cluster_id);

-- (strategy, area, orderIndex) — a strategy's areas are an ORDERED list; PUT rewrites this
-- whole list assigning orderIndex 1..N (myWMS `saveForStorageStrategy` shape).
CREATE TABLE IF NOT EXISTS storage_strategy_areas (
    id                   BIGSERIAL PRIMARY KEY,
    version              INT NOT NULL DEFAULT 0,
    created               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    storage_strategy_id  BIGINT NOT NULL REFERENCES storage_strategies(id),
    storage_area_id      BIGINT NOT NULL REFERENCES storage_areas(id),
    order_index          INT NOT NULL DEFAULT 0,
    UNIQUE(storage_strategy_id, storage_area_id)
);

CREATE INDEX idx_strategy_areas_strategy ON storage_strategy_areas(storage_strategy_id);
CREATE INDEX idx_strategy_areas_area ON storage_strategy_areas(storage_area_id);

-- Per-product planned occupancy threshold for a storage area (myWMS useItemDataArea).
-- item_data_id is a FOREIGN MODULE id (product) — deliberately NO FK, validated at the
-- service layer via the ProductLookup SPI instead.
CREATE TABLE IF NOT EXISTS item_data_areas (
    id              BIGSERIAL PRIMARY KEY,
    version         INT NOT NULL DEFAULT 0,
    created         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id       BIGINT NOT NULL,
    item_data_id    BIGINT NOT NULL,
    storage_area_id BIGINT NOT NULL REFERENCES storage_areas(id),
    planned_amount  NUMERIC(17,4),
    planned_stocks  INT,
    UNIQUE(item_data_id, storage_area_id)
);

CREATE INDEX idx_item_data_areas_client ON item_data_areas(client_id);
CREATE INDEX idx_item_data_areas_item ON item_data_areas(item_data_id);
CREATE INDEX idx_item_data_areas_area ON item_data_areas(storage_area_id);
