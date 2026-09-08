CREATE TABLE IF NOT EXISTS asn_ul_advices (
    id                       BIGSERIAL PRIMARY KEY,
    version                  INT NOT NULL DEFAULT 0,
    created                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    asn_id                   BIGINT NOT NULL REFERENCES asns(id) ON DELETE CASCADE,
    label_id                 VARCHAR(100) NOT NULL,
    unit_load_type_id        BIGINT,
    item_data_id             BIGINT,
    item_data_number         VARCHAR(100),
    expected_amount          NUMERIC(17,4),
    reason_for_return        VARCHAR(255),
    state                    INT NOT NULL DEFAULT 50,
    matched_receipt_line_id  BIGINT,
    CONSTRAINT uq_asn_ul_advices_label UNIQUE (asn_id, label_id)
);

CREATE INDEX idx_asn_ul_advices_asn ON asn_ul_advices(asn_id);
