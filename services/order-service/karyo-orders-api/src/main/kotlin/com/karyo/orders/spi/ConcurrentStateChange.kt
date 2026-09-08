package com.karyo.orders.spi

/**
 * Marker for exceptions meaning "somebody else moved this entity first" -- a benign concurrent
 * state race, not a broken entity. The streaming engine skips (never stalls) any failure whose
 * cause chain carries this marker (streaming spec ruling 12: manual release wins). Declared in
 * orders-api so paid modules match it structurally without depending on the Apache-2.0 orders
 * core (module dependency direction, streaming spec ruling 1).
 */
interface ConcurrentStateChange
