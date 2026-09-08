INSERT INTO order_strategies (name, use_locked_stock, prefer_complete)
VALUES ('DEFAULT', FALSE, TRUE)
ON CONFLICT (name) DO NOTHING;
