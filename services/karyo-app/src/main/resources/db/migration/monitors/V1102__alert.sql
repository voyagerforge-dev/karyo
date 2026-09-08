CREATE TABLE alerts (
    id             BIGSERIAL PRIMARY KEY,
    version        INT         NOT NULL DEFAULT 0,
    created        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id      BIGINT      NOT NULL,
    monitor_key    VARCHAR(64) NOT NULL,
    severity       INT         NOT NULL DEFAULT 2,
    status         VARCHAR(16) NOT NULL DEFAULT 'FIRING',
    scope          VARCHAR(128) NOT NULL DEFAULT '',
    reason         VARCHAR(512) NOT NULL DEFAULT '',
    suggested_fix  VARCHAR(512) NOT NULL DEFAULT '',
    observed_value DOUBLE PRECISION NOT NULL DEFAULT 0,
    first_fired_at TIMESTAMPTZ NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL,
    resolved_at    TIMESTAMPTZ
);
CREATE INDEX idx_alerts_client_status ON alerts (client_id, status);
CREATE INDEX idx_alerts_open ON alerts (client_id, monitor_key, scope, status);
