CREATE TABLE IF NOT EXISTS count_campaigns (
    id               BIGSERIAL PRIMARY KEY,
    version          INT NOT NULL DEFAULT 0,
    created          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id        BIGINT NOT NULL,
    campaign_number  VARCHAR(40) NOT NULL,
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(20) NOT NULL DEFAULT 'CYCLE',
    state            INT NOT NULL DEFAULT 100,
    started          TIMESTAMPTZ,
    ended            TIMESTAMPTZ,
    UNIQUE (client_id, campaign_number)
);
CREATE INDEX IF NOT EXISTS idx_count_campaigns_client ON count_campaigns(client_id);

-- Nullable: sessions started without a campaign stay legal (the demo generator writes
-- CountSession rows directly and must keep working unchanged).
ALTER TABLE count_sessions ADD COLUMN IF NOT EXISTS campaign_id BIGINT REFERENCES count_campaigns(id);
CREATE INDEX IF NOT EXISTS idx_count_sessions_campaign ON count_sessions(campaign_id);
