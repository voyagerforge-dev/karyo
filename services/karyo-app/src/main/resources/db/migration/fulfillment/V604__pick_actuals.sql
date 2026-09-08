-- Picked lot/best-before actuals (WORKLIST row 18): the durable record of what was actually
-- picked, captured from the source stock unit at confirm time BEFORE the stock is mutated.
-- picks.lot_number (existing) is the PLANNED lot carried from the reservation; these two columns
-- are the picked ACTUALS -- distinct concepts kept as distinct columns, never overwritten.
-- picked_best_before mirrors StockContentRef.bestBefore's type (java.time.LocalDate -> DATE).
ALTER TABLE picks
    ADD COLUMN picked_lot_number VARCHAR(255),
    ADD COLUMN picked_best_before DATE;
