-- Phase B (B19): saved-reports store. A tenant-scoped list of named report configs
-- (report_type + arbitrary JSONB params) an operator can save/reuse from the Reports page.
CREATE TABLE IF NOT EXISTS report_definitions (
    id           BIGSERIAL PRIMARY KEY,
    version      INT NOT NULL DEFAULT 0,
    created      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id    BIGINT NOT NULL,
    name         VARCHAR(200) NOT NULL,
    report_type  VARCHAR(60) NOT NULL,
    params       JSONB NOT NULL DEFAULT '{}'::jsonb,
    owner        VARCHAR(100)
);

CREATE INDEX idx_report_definitions_client ON report_definitions(client_id);
