-- V1401: named selection rules + wave selection provenance (wave module, Advanced Fulfillment pack)
CREATE TABLE wave_selection_rules (
    id          BIGSERIAL    PRIMARY KEY,
    version     INT          NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    modified    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    client_id   BIGINT       NOT NULL DEFAULT 0,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    definition  JSONB        NOT NULL,
    CONSTRAINT uq_wave_selection_rules_client_name UNIQUE (client_id, name)
);
ALTER TABLE waves ADD COLUMN selection_strategy VARCHAR(40);
ALTER TABLE waves ADD COLUMN selection_rule_id BIGINT;
