package com.karyo.fulfillment.vo

/**
 * myWMS pick classification (relaxed for v1.3 — area/fixed-location checks deferred to Spec B):
 *  - COMPLETE: the whole source stock unit is taken (planned == source amount) — a full-unit move.
 *  - PICK: a partial quantity is taken from the source.
 *  - EXTINGUISH: a stock-clearance pick (WORKLIST row 20) — no backing DeliveryOrder line;
 *    always takes the FULL `availableAmount` of its source stock unit.
 */
enum class PickingType { COMPLETE, PICK, EXTINGUISH }
