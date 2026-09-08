CREATE TABLE IF NOT EXISTS work_groups (
    id          BIGSERIAL PRIMARY KEY,
    version     INT NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id   BIGINT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    work_types  VARCHAR(200) NOT NULL,          -- CSV of WorkType names, e.g. 'PUTAWAY,MOVE,REPLENISH'
    zones       VARCHAR(500),                   -- CSV of zone names; NULL = all zones
    UNIQUE (client_id, name)
);

CREATE TABLE IF NOT EXISTS work_group_members (
    id            BIGSERIAL PRIMARY KEY,
    version       INT NOT NULL DEFAULT 0,
    created       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id     BIGINT NOT NULL,
    work_group_id BIGINT NOT NULL,
    operator_id   VARCHAR(100) NOT NULL,
    UNIQUE (work_group_id, operator_id)
);

CREATE INDEX IF NOT EXISTS idx_work_group_members_operator ON work_group_members (client_id, operator_id);
