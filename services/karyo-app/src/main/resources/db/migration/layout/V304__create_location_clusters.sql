CREATE TABLE IF NOT EXISTS location_clusters (
    id                BIGSERIAL PRIMARY KEY,
    version           INT NOT NULL DEFAULT 0,
    created           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name              VARCHAR(100) NOT NULL UNIQUE,
    parent_cluster_id BIGINT REFERENCES location_clusters(id)
);
