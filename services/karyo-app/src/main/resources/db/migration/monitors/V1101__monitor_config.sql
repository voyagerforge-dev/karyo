CREATE TABLE monitor_config (
    id            BIGSERIAL PRIMARY KEY,
    version       INT         NOT NULL DEFAULT 0,
    created       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id     BIGINT      NOT NULL,
    monitor_key   VARCHAR(64) NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    threshold     DOUBLE PRECISION NOT NULL DEFAULT 0,
    severity      INT         NOT NULL DEFAULT 2,
    op            VARCHAR(2)  NOT NULL DEFAULT '>',
    channels_json VARCHAR(256) NOT NULL DEFAULT '{"push":false,"email":false,"slack":false}',
    esc           BOOLEAN     NOT NULL DEFAULT FALSE,
    esc_min       INT         NOT NULL DEFAULT 15,
    CONSTRAINT uq_monitor_config_client_key UNIQUE (client_id, monitor_key)
);
CREATE INDEX idx_monitor_config_client ON monitor_config (client_id);
