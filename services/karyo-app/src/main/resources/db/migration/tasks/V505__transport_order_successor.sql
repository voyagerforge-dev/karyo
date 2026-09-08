-- PT15 (putaway-transport sprint, Task 2): the TRANSFER successor a predecessor order
-- spawns when it completes onto a transfer-staging area location while still carrying a
-- different real final target (see ChainContinuationService). NULL for every order that
-- never chained. The relationship is RECURSIVE, not one-hop: a successor can itself spawn
-- a further successor if it completes short of the chain's real final target on another
-- transfer-staging location, so a chain is walked by following successor_id forward from
-- the first order until it is NULL, not by assuming at most one hop.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS successor_id BIGINT REFERENCES transport_orders(id);

CREATE INDEX IF NOT EXISTS idx_transport_orders_successor ON transport_orders(successor_id);
