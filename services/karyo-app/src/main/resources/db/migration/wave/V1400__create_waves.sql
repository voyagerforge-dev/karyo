-- V1400: waves + consolidation_groups (wave module, Advanced Fulfillment pack)
CREATE TABLE waves (
    id                  BIGSERIAL    PRIMARY KEY,
    version             INT          NOT NULL DEFAULT 0,
    created             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    modified            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    client_id           BIGINT       NOT NULL DEFAULT 0,
    wave_number         VARCHAR(80)  NOT NULL UNIQUE,
    state               INTEGER      NOT NULL DEFAULT 100,
    order_strategy_id   BIGINT       NOT NULL,
    wave_pick_mode      VARCHAR(20)  NOT NULL DEFAULT 'HYBRID',
    shortage_action     VARCHAR(20)  NOT NULL DEFAULT 'SKIP',
    planned_release_at  TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    total_orders        INT          NOT NULL DEFAULT 0,
    total_lines         INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_waves_client_state ON waves (client_id, state);

CREATE TABLE consolidation_groups (
    id                        BIGSERIAL    PRIMARY KEY,
    version                   INT          NOT NULL DEFAULT 0,
    created                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    modified                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    client_id                 BIGINT       NOT NULL DEFAULT 0,
    wave_id                   BIGINT       NOT NULL,
    destination_key           VARCHAR(200) NOT NULL,
    consolidation_location_id BIGINT,
    state                     INTEGER      NOT NULL DEFAULT 100,
    total_pick_orders         INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_cg_wave ON consolidation_groups (wave_id);
