-- L5 (locations-layout sprint, Task 8): the myWMS LOSWorkingArea port — a named SET of
-- LocationClusters modeling an operator workstation's reach. Shape mirrors V311's
-- storage_areas/storage_area_clusters exactly (same M2M-over-clusters pattern), but this
-- is a DIFFERENT concept from StorageArea: StorageArea restricts/hides putaway candidates
-- inside the finder; WorkingArea scopes which OFFERED WORK ITEMS an operator sees (no
-- finder interaction at all). Deliberately NO user binding (myWMS has none — the operator
-- chooses a working area per request; see WorkingAreaLookup KDoc).
CREATE TABLE IF NOT EXISTS working_areas (
    id       BIGSERIAL PRIMARY KEY,
    version  INT NOT NULL DEFAULT 0,
    created  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name     VARCHAR(255) NOT NULL UNIQUE
);

-- Pure M2M join: a location is "in" a working area iff its cluster is a member here.
-- ON DELETE CASCADE both ways — this table has no independent identity/semantics of its own.
CREATE TABLE IF NOT EXISTS working_area_clusters (
    working_area_id     BIGINT NOT NULL REFERENCES working_areas(id) ON DELETE CASCADE,
    location_cluster_id BIGINT NOT NULL REFERENCES location_clusters(id) ON DELETE CASCADE,
    PRIMARY KEY (working_area_id, location_cluster_id)
);

CREATE INDEX idx_working_area_clusters_cluster ON working_area_clusters(location_cluster_id);
