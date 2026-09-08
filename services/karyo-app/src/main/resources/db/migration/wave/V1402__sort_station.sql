-- V1402: sort station (Bulk Allocation Sprint A). Full-address destination key, per-wave sort
-- slot label, sorted counters per group line, per-put audit trail. Expected quantities are
-- never stored here: they are derived live from PICKED batch picks (fulfillment) at read time.
ALTER TABLE consolidation_groups ALTER COLUMN destination_key TYPE VARCHAR(1000);
ALTER TABLE consolidation_groups ADD COLUMN sort_slot VARCHAR(8) NOT NULL DEFAULT '';

CREATE TABLE consolidation_lines (
    id                     BIGSERIAL      PRIMARY KEY,
    version                INT            NOT NULL DEFAULT 0,
    created                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    modified               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    client_id              BIGINT         NOT NULL DEFAULT 0,
    wave_id                BIGINT         NOT NULL,
    group_id               BIGINT         NOT NULL,
    delivery_order_line_id BIGINT         NOT NULL,
    item_data_id           BIGINT         NOT NULL,
    item_data_number       VARCHAR(255)   NOT NULL,
    lot_number             VARCHAR(255)   NOT NULL DEFAULT '',
    cart_unit_load_id      BIGINT         NOT NULL,
    sorted_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    CONSTRAINT uq_consolidation_lines
        UNIQUE (client_id, group_id, delivery_order_line_id, lot_number, cart_unit_load_id)
);
CREATE INDEX idx_consolidation_lines_wave ON consolidation_lines (client_id, wave_id);

CREATE TABLE sort_scans (
    id                     BIGSERIAL      PRIMARY KEY,
    version                INT            NOT NULL DEFAULT 0,
    created                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    modified               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    client_id              BIGINT         NOT NULL DEFAULT 0,
    wave_id                BIGINT         NOT NULL,
    group_id               BIGINT         NOT NULL,
    delivery_order_line_id BIGINT         NOT NULL,
    cart_unit_load_id      BIGINT         NOT NULL,
    item_data_id           BIGINT         NOT NULL,
    lot_number             VARCHAR(255)   NOT NULL DEFAULT '',
    amount                 NUMERIC(19, 4) NOT NULL,
    operator_id            VARCHAR(100)   NOT NULL,
    scanned_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    undone_at              TIMESTAMPTZ,
    undone_by              VARCHAR(100)
);
CREATE INDEX idx_sort_scans_wave ON sort_scans (client_id, wave_id);

-- Upgrade path for groups formed BEFORE this migration (final fix wave, F2). Their
-- destination_key is the old three-part `customerName|zipCode|city`; every reader now derives the
-- six-part full-address key, so a stale row would silently match ZERO members and fail open
-- (readinessBlocker, WaveProgressObserver and the sort station's picture()). Best effort: rewrite
-- each old-shaped key to the six-part key of its first matching member delivery order, chosen
-- deterministically (ORDER BY id LIMIT 1). Groups with no matching member are left untouched.
--
-- concat_ws is deliberately NOT used: it SKIPS nulls, which would collapse separators and produce
-- a key ConsolidationKey.of can never emit. coalesce(x, '') joined by '|' reproduces
-- ConsolidationKey.of exactly, empty parts included. The old shape is detected by pipe count
-- (exactly two), so an already-six-part key is never touched and this migration stays idempotent
-- in effect.
UPDATE consolidation_groups cg
SET destination_key = (
        SELECT coalesce(d.customer_name, '') || '|' || coalesce(d.street, '') || '|' ||
               coalesce(d.street_number, '') || '|' || coalesce(d.zip_code, '') || '|' ||
               coalesce(d.city, '') || '|' || coalesce(d.country, '')
        FROM delivery_orders d
        WHERE d.wave_id = cg.wave_id
          AND coalesce(d.customer_name, '') || '|' || coalesce(d.zip_code, '') || '|' ||
              coalesce(d.city, '') = cg.destination_key
        ORDER BY d.id
        LIMIT 1
    )
WHERE length(cg.destination_key) - length(replace(cg.destination_key, '|', '')) = 2
  AND EXISTS (
        SELECT 1
        FROM delivery_orders d
        WHERE d.wave_id = cg.wave_id
          AND coalesce(d.customer_name, '') || '|' || coalesce(d.zip_code, '') || '|' ||
              coalesce(d.city, '') = cg.destination_key
    );
