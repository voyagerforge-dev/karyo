CREATE TABLE IF NOT EXISTS item_units (
    id          BIGSERIAL PRIMARY KEY,
    version     INT NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name        VARCHAR(20) NOT NULL UNIQUE,
    unit_type   VARCHAR(20) NOT NULL DEFAULT 'PIECE'
);

INSERT INTO item_units (name, unit_type) VALUES
    ('PCS', 'PIECE'), ('KG', 'WEIGHT'), ('L', 'VOLUME'),
    ('M', 'LENGTH'), ('BOX', 'PIECE'), ('PAL', 'PIECE')
ON CONFLICT (name) DO NOTHING;
