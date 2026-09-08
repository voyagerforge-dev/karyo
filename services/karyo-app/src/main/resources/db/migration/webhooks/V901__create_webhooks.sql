CREATE TABLE webhook_subscription (
    id           BIGSERIAL PRIMARY KEY,
    version      INT NOT NULL DEFAULT 0,
    created      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id    BIGINT NOT NULL,
    name         VARCHAR(120) NOT NULL,
    target_url   VARCHAR(2048) NOT NULL,
    secret       VARCHAR(128) NOT NULL,
    event_types  JSONB NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_webhook_subscription_active ON webhook_subscription(active);

CREATE TABLE webhook_delivery (
    id                 BIGSERIAL PRIMARY KEY,
    version            INT NOT NULL DEFAULT 0,
    created            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    subscription_id    BIGINT NOT NULL REFERENCES webhook_subscription(id) ON DELETE CASCADE,
    tenant_id          BIGINT NOT NULL,
    outbox_event_id    BIGINT,
    event_type         VARCHAR(100) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    attempts           INT NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_response_code INT,
    last_error         TEXT,
    delivered_at       TIMESTAMPTZ
);
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery(status, next_attempt_at);
CREATE INDEX idx_webhook_delivery_sub ON webhook_delivery(subscription_id, created DESC);
-- Idempotent fan-out: a (subscription, real outbox event) pair can exist once.
-- Synthetic pings (null outbox id) are exempt.
CREATE UNIQUE INDEX uq_webhook_delivery_event
    ON webhook_delivery(subscription_id, outbox_event_id)
    WHERE outbox_event_id IS NOT NULL;

-- Single-row fan-out watermark (last outbox id turned into deliveries).
CREATE TABLE webhook_fanout_cursor (
    id             INT PRIMARY KEY DEFAULT 1,
    last_outbox_id BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT webhook_fanout_cursor_singleton CHECK (id = 1)
);
INSERT INTO webhook_fanout_cursor (id, last_outbox_id) VALUES (1, 0) ON CONFLICT DO NOTHING;
