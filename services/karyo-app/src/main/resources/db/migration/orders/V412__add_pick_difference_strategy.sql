ALTER TABLE order_strategies
    ADD COLUMN IF NOT EXISTS pick_difference_strategy VARCHAR(60) NOT NULL DEFAULT 'LEAVE';
