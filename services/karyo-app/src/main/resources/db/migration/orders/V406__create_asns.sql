CREATE TABLE IF NOT EXISTS asns (
    id               BIGSERIAL PRIMARY KEY,
    version          INT NOT NULL DEFAULT 0,
    created          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id        BIGINT NOT NULL,
    asn_number       VARCHAR(100) NOT NULL,
    external_number  VARCHAR(100),
    carrier_name     VARCHAR(255),
    expected_date    DATE,
    notes            VARCHAR(2000),
    state            INT NOT NULL DEFAULT 0,
    UNIQUE(client_id, asn_number)
);

CREATE INDEX idx_asns_client ON asns(client_id);
CREATE INDEX idx_asns_state ON asns(state);
CREATE INDEX idx_asns_number ON asns(asn_number);
