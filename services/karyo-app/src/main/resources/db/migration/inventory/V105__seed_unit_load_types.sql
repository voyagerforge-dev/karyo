INSERT INTO unit_load_types (name, usages, aggregate_stocks) VALUES
    ('Euro Pallet', 'FORKLIFT,STORAGE,COMPLETE', FALSE),
    ('Pick Bin', 'PICKING', TRUE),
    ('Shipping Carton', 'SHIPPING,PACKING', TRUE),
    ('Virtual', 'PICKING,STORAGE', TRUE)
ON CONFLICT (name) DO NOTHING;
