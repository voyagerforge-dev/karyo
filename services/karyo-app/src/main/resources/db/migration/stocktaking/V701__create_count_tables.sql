CREATE TABLE IF NOT EXISTS count_sessions (
    id              BIGSERIAL PRIMARY KEY,
    version         INT NOT NULL DEFAULT 0,
    created         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id       BIGINT NOT NULL,
    session_number  VARCHAR(40) NOT NULL,
    type            VARCHAR(20) NOT NULL DEFAULT 'CYCLE',
    state           INT NOT NULL DEFAULT 100,
    started         TIMESTAMPTZ,
    ended           TIMESTAMPTZ,
    UNIQUE (client_id, session_number)
);
CREATE INDEX idx_count_sessions_client ON count_sessions(client_id);

CREATE TABLE IF NOT EXISTS count_orders (
    id              BIGSERIAL PRIMARY KEY,
    version         INT NOT NULL DEFAULT 0,
    created         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id       BIGINT NOT NULL,
    session_id      BIGINT NOT NULL REFERENCES count_sessions(id),
    order_number    VARCHAR(40) NOT NULL,
    location_id     BIGINT NOT NULL,
    location_name   VARCHAR(100) NOT NULL,
    state           INT NOT NULL DEFAULT 50,
    blind_count     BOOLEAN NOT NULL DEFAULT TRUE,
    started         TIMESTAMPTZ,
    finished        TIMESTAMPTZ,
    UNIQUE (client_id, order_number)
);
CREATE INDEX idx_count_orders_client ON count_orders(client_id);
CREATE INDEX idx_count_orders_session ON count_orders(session_id);
CREATE INDEX idx_count_orders_state ON count_orders(client_id, state);

CREATE TABLE IF NOT EXISTS count_lines (
    id                BIGSERIAL PRIMARY KEY,
    version           INT NOT NULL DEFAULT 0,
    created           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id         BIGINT NOT NULL,
    count_order_id    BIGINT NOT NULL REFERENCES count_orders(id),
    stock_unit_id     BIGINT NOT NULL,
    item_data_id      BIGINT NOT NULL,
    item_data_number  VARCHAR(100) NOT NULL,
    lot_number        VARCHAR(255),
    serial_number     VARCHAR(255),
    planned_amount    NUMERIC(17,4) NOT NULL,
    counted_amount    NUMERIC(17,4),
    state             INT NOT NULL DEFAULT 50,
    UNIQUE (client_id, count_order_id, stock_unit_id)
);
CREATE INDEX idx_count_lines_client ON count_lines(client_id);
CREATE INDEX idx_count_lines_order ON count_lines(count_order_id);
