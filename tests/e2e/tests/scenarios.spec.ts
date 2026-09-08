/**
 * v1.3 Acceptance Scenario Suite -- 20 business-level scenarios exercising every
 * major capability of the deployed stack on http://localhost:8088.
 *
 * Plan: docs/testing/2026-06-15-v1.3-acceptance-scenarios.md
 *
 * Each test() is one scenario, seeds its own uniquely-named data (so the suite is
 * order-insensitive), and is API-driven via the captured bearer header. Guard
 * scenarios use rawPost/rawGet (which never throw) to assert expected 4xx codes.
 *
 * Run: cd tests/e2e && BASE_URL=http://localhost:8088 npx playwright test tests/scenarios.spec.ts --reporter=list
 */
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import {
  type Headers,
  ON_STOCK,
  PICKED,
  confirmPick,
  createOrder,
  driveToPacked,
  driveToPicked,
  get,
  getOrder,
  getPickOrder,
  openPacking,
  packShipment,
  post,
  rawGet,
  rawPost,
  releaseOrder,
  releaseToPicking,
  seedAreas,
  seedProduct,
  seedStockOnUL,
  uniq,
  type Entity,
  type Shipment,
} from "../fixtures/scenario-helpers";

// Order states (from com.karyo.orders.vo.OrderState).
const RELEASED = 100;
const PROCESSABLE = 300;
const PENDING = 550;
const ORDER_PICKED = 600;
const ORDER_PACKED = 650;
const ORDER_SHIPPED = 680;
const FINISHED = 700;

// Shipment states (from com.karyo.fulfillment.domain.model ShipmentState).
const SHIP_PACKING = 640;
const SHIP_PACKED = 650;
const SHIP_SHIPPING = 670;
const SHIP_SHIPPED = 680;

const num = (v: string | number): number => Number(v);

test.describe("v1.3 acceptance scenarios", () => {
  test.describe.configure({ timeout: 180_000 });

  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // same fix as putaway.spec.ts / picking.spec.ts / packing.spec.ts.
  test.use({ credentials: "manager" });

  // ====================================================================
  // Master data & inventory
  // ====================================================================

  test("1. Product master lifecycle", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units", h);
    expect(itemUnits.length).toBeGreaterThan(0);

    const sku = uniq("S1").toUpperCase();
    // ProductResponse nests the item-unit (`itemUnit: {id,...}`), not a flat id.
    const created = await post<{ id: number; number: string; itemUnit: { id: number } }>(
      page,
      "/api/v1/products",
      {
        number: sku,
        name: `s1-${sku}`,
        description: "scenario 1 product",
        weight: 0.5,
        itemUnitId: itemUnits[0].id,
      },
      h,
    );
    expect(created.number).toBe(sku);
    expect(created.itemUnit.id).toBe(itemUnits[0].id);

    const byId = await get<{ id: number; number: string; itemUnit: { id: number } }>(
      page,
      `/api/v1/products/${created.id}`,
      h,
    );
    expect(byId.number).toBe(sku);
    expect(byId.itemUnit.id).toBe(itemUnits[0].id);

    const byNumber = await get<{ id: number; number: string }>(
      page,
      `/api/v1/products/by-number/${sku}`,
      h,
    );
    expect(byNumber.id).toBe(created.id);
    expect(byNumber.number).toBe(sku);
  });

  test("2. Inventory availability invariant", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s2");
    const product = await seedProduct(page, h, "s2");
    const { stockUnitId } = await seedStockOnUL(page, h, {
      product,
      areas,
      amount: 100,
      prefix: "s2",
    });

    // Before release: available 100, reserved 0.
    const before = await get<{ amount: string; reservedAmount: string; availableAmount: string }>(
      page,
      `/api/v1/stock-units/${stockUnitId}`,
      h,
    );
    expect(num(before.availableAmount)).toBe(100);
    expect(num(before.reservedAmount)).toBe(0);

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 40 }],
      prefix: "s2",
    });
    await releaseOrder(page, h, order.id);

    // After release: reserved 40, available 60 (never negative; available = amount - reserved).
    const after = await get<{ amount: string; reservedAmount: string; availableAmount: string }>(
      page,
      `/api/v1/stock-units/${stockUnitId}`,
      h,
    );
    expect(num(after.reservedAmount)).toBe(40);
    expect(num(after.availableAmount)).toBe(60);
    expect(num(after.amount) - num(after.reservedAmount)).toBe(num(after.availableAmount));
    expect(num(after.availableAmount)).toBeGreaterThanOrEqual(0);
  });

  test("3. Stock state machine is forward-only", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s3");
    const product = await seedProduct(page, h, "s3");
    // Seed already at ON_STOCK(300).
    const { stockUnitId } = await seedStockOnUL(page, h, {
      product,
      areas,
      amount: 10,
      state: ON_STOCK,
      prefix: "s3",
    });

    // Forward 300 -> 600 must succeed.
    const fwd = await post<{ state: number }>(
      page,
      `/api/v1/stock-units/${stockUnitId}/change-state`,
      { state: PICKED },
      h,
    );
    expect(fwd.state).toBe(PICKED);

    // Backward 600 -> 300 must be rejected (4xx).
    const back = await rawPost(
      page,
      `/api/v1/stock-units/${stockUnitId}/change-state`,
      { state: ON_STOCK },
      h,
    );
    expect(back.status(), `backward 600->300 body: ${await back.text()}`).toBeGreaterThanOrEqual(400);
    expect(back.status()).toBeLessThan(500);
  });

  // ====================================================================
  // Layout & putaway
  // ====================================================================

  test("4. Layout CRUD", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const s = uniq("s4");

    const locType = await post<{ id: number; name: string }>(
      page,
      "/api/v1/location-types",
      { name: `${s}-lt`, height: 200, width: 100, depth: 120, liftingCapacity: 500 },
      h,
    );
    const area = await post<{ id: number; name: string }>(
      page,
      "/api/v1/areas",
      { name: `${s}-area`, usages: ["STORAGE"] },
      h,
    );
    const locName = `${s}-loc`;
    const loc = await post<{ id: number; name: string; areaId: number; locationTypeId: number }>(
      page,
      "/api/v1/locations",
      { name: locName, scanCode: locName, locationTypeId: locType.id, areaId: area.id },
      h,
    );

    const ltBack = await get<{ id: number; name: string }>(page, `/api/v1/location-types/${locType.id}`, h);
    expect(ltBack.id).toBe(locType.id);
    const areaBack = await get<{ id: number; name: string }>(page, `/api/v1/areas/${area.id}`, h);
    expect(areaBack.id).toBe(area.id);
    // LocationResponse nests area + locationType (not flat ids).
    const locBack = await get<{
      id: number;
      area: { id: number };
      locationType: { id: number };
    }>(page, `/api/v1/locations/${loc.id}`, h);
    expect(locBack.area.id).toBe(area.id);
    expect(locBack.locationType.id).toBe(locType.id);
  });

  // Scenario 5 ("Putaway location finder") removed 2026-07-19 with the /api/internal
  // router it drove. That router was dead code from the microservice era -- no production
  // caller -- and it was publicly routable, with its stock-selection sibling taking the
  // client scope from a caller-supplied query parameter.
  //
  // No coverage is lost: putaway.spec.ts exercises the location finder through the real
  // production path (receive -> auto-putaway -> transport order with a destination ->
  // assign -> start -> complete -> stock moved to storage), which is a stronger assertion
  // than this scenario's direct `found: true` poke at the REST wrapper.

  // ====================================================================
  // Orders, strategy & receiving (inbound)
  // ====================================================================

  test("6. Order reservation happy path", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s6");
    const product = await seedProduct(page, h, "s6");
    await seedStockOnUL(page, h, { product, areas, amount: 100, prefix: "s6" });

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 60 }],
      prefix: "s6",
    });
    const released = await releaseOrder(page, h, order.id);

    // Fully reserved -> PROCESSABLE(300) (or RESERVED 400); line reserved 60, shortage 0.
    expect([PROCESSABLE, 400]).toContain(released.state);
    const line = released.lines[0];
    expect(num(line.reservedAmount)).toBe(60);
    expect(num(line.shortage)).toBe(0);
  });

  test("7. Order shortage handling", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s7");
    const product = await seedProduct(page, h, "s7");
    await seedStockOnUL(page, h, { product, areas, amount: 60, prefix: "s7" });

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 100 }],
      prefix: "s7",
    });
    const released = await releaseOrder(page, h, order.id);

    const line = released.lines[0];
    expect(num(line.shortage)).toBe(40);
    // Per OrderService design: the ORDER stays RELEASED(100) on a shortfall (it
    // never enters PENDING); the short LINE goes PENDING(550). Assert both, plus
    // non-FINISHED.
    expect(released.state).not.toBe(FINISHED);
    expect(released.state).toBe(RELEASED);
    expect(line.state).toBe(PENDING);
  });

  test("8. Order strategy round-trip", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };

    const strategy = await post<{
      id: number;
      shortPickMode: string;
      preferComplete: boolean;
      enforceLot: boolean;
      packoutStrategy: string;
    }>(
      page,
      "/api/v1/order-strategies",
      {
        name: uniq("s8-strat"),
        preferComplete: true,
        enforceLot: true,
        shortPickMode: "FOLLOW_UP_THEN_SUBSTITUTE",
        packoutStrategy: "ONE_TO_ONE",
      },
      h,
    );

    const back = await get<{
      id: number;
      shortPickMode: string;
      preferComplete: boolean;
      enforceLot: boolean;
      packoutStrategy: string;
    }>(page, `/api/v1/order-strategies/${strategy.id}`, h);
    expect(back.shortPickMode).toBe("FOLLOW_UP_THEN_SUBSTITUTE");
    expect(back.preferComplete).toBe(true);
    expect(back.enforceLot).toBe(true);
    expect(back.packoutStrategy).toBe("ONE_TO_ONE");

    // An order referencing the strategy carries orderStrategyId.
    const product = await seedProduct(page, h, "s8");
    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 5 }],
      orderStrategyId: strategy.id,
      prefix: "s8",
    });
    expect(order.orderStrategyId).toBe(strategy.id);
  });

  test("9. ASN -> receiving -> stock (inbound)", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s9");
    const product = await seedProduct(page, h, "s9");

    // Create an ASN expecting 100 of the product, then release it.
    const asn = await post<{ id: number; lines: Array<{ id: number; itemDataId: number }> }>(
      page,
      "/api/v1/asns",
      {
        asnNumber: uniq("s9-asn"),
        lines: [{ itemDataId: product.id, expectedAmount: 100 }],
      },
      h,
    );
    await post(page, `/api/v1/asns/${asn.id}/release`, {}, h);

    // Open a goods receipt against the ASN, then receive its line into stock.
    const receipt = await post<{ id: number }>(
      page,
      "/api/v1/goods-receipts",
      { receiptNumber: uniq("s9-gr"), asnId: asn.id },
      h,
    );
    const received = await post<{
      stockUnitId: number;
      unitLoadId: number;
      receipt: { state: number };
    }>(
      page,
      `/api/v1/goods-receipts/${receipt.id}/lines`,
      {
        asnLineId: asn.lines[0].id,
        amount: 100,
        locationId: areas.storeLocId,
        locationName: areas.storeLocName,
        unitLoadTypeId: areas.unitLoadTypeId,
      },
      h,
    );

    // Stock now exists for the received item, tied to the receipt's created stock unit.
    expect(received.stockUnitId).toBeTruthy();
    const stock = await get<{ itemDataId: number; amount: string; state: number }>(
      page,
      `/api/v1/stock-units/${received.stockUnitId}`,
      h,
    );
    expect(stock.itemDataId).toBe(product.id);
    expect(num(stock.amount)).toBe(100);
    // Received stock lands INCOMING(100) (blind/QA path) or ON_STOCK(300).
    expect([100, ON_STOCK]).toContain(stock.state);
  });

  // ====================================================================
  // Picking (outbound -- the rich path)
  // ====================================================================

  test("10. Full pick -> order PICKED", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s10");
    const { order, pickOrder } = await driveToPicked(page, h, { areas, amount: 60, prefix: "s10" });

    const po = await getPickOrder(page, h, pickOrder.id);
    expect(po.state).toBe(PICKED);

    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(ORDER_PICKED);

    // Picked stock dwells on the pick container (target unit-load) at PICKED(600).
    expect(po.targetUnitLoadId).toBeTruthy();
    const containerStock = await get<Array<{ state: number; amount: string }>>(
      page,
      `/api/v1/stock-units?unitLoadId=${po.targetUnitLoadId}`,
      h,
    );
    const items = Array.isArray(containerStock)
      ? containerStock
      : (containerStock as unknown as { content: Array<{ state: number }> }).content;
    expect(items.length).toBeGreaterThan(0);
    expect(items.every((su) => su.state === PICKED)).toBe(true);
  });

  test("11. Short pick -> follow-up covers it", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s11");
    const product = await seedProduct(page, h, "s11");
    // Two source units: A=60 (FIFO-preferred), B=40 (the follow-up source).
    await seedStockOnUL(page, h, { product, areas, amount: 60, prefix: "s11a" });
    await seedStockOnUL(page, h, { product, areas, amount: 40, prefix: "s11b" });

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 60 }],
      prefix: "s11",
    });
    await releaseOrder(page, h, order.id);
    const po = await releaseToPicking(page, h, order.id);

    // Short-confirm the first pick at 50 of 60.
    await confirmPick(page, h, po.picks[0].id, 50);

    const after = await getPickOrder(page, h, po.id);
    const followUp = after.picks.find((p) => p.followUpForPickId != null);
    expect(followUp, "a follow-up pick must appear after a short pick").toBeTruthy();
    expect(followUp!.state).toBe(100); // RELEASED

    // Confirm the follow-up at its planned amount -> order PICKED.
    await confirmPick(page, h, followUp!.id, Number(followUp!.plannedAmount));
    const done = await getPickOrder(page, h, po.id);
    expect(done.state).toBe(PICKED);
    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(ORDER_PICKED);
  });

  test("12. Short pick -> 1:1 substitution covers it", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s12");
    const productA = await seedProduct(page, h, "s12a");
    const productB = await seedProduct(page, h, "s12b");

    // A->B substitution master.
    await post(
      page,
      "/api/v1/item-substitutions",
      { itemDataId: productA.id, substituteItemDataId: productB.id, priority: 1 },
      h,
    );

    // A=60 fully reserves the 60-line (one pick of 60). NO spare A beyond the pick,
    // so a short-confirm cannot be covered by a same-item follow-up and must fall
    // through to the B substitute (default shortPickMode FOLLOW_UP_THEN_SUBSTITUTE).
    // B=20 covers the uncovered 10.
    await seedStockOnUL(page, h, { product: productA, areas, amount: 60, prefix: "s12a" });
    await seedStockOnUL(page, h, { product: productB, areas, amount: 20, prefix: "s12b" });

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: productA.id, itemDataNumber: productA.number, amount: 60 }],
      prefix: "s12",
    });
    const released = await releaseOrder(page, h, order.id);
    const po = await releaseToPicking(page, h, order.id);

    // Short-confirm the A pick at 50 of 60: no spare A -> substitution to B for the 10.
    await confirmPick(page, h, po.picks[0].id, 50);

    const after = await getPickOrder(page, h, po.id);
    const sub = after.picks.find((p) => p.substitutedItemDataId != null);
    expect(
      sub,
      `expected a substitution follow-up (B=${productB.id}); released line reserved=${num(
        released.lines[0].reservedAmount,
      )}; picks=${JSON.stringify(after.picks.map((p) => ({ id: p.id, st: p.state, fu: p.followUpForPickId, sub: p.substitutedItemDataId })))}`,
    ).toBeTruthy();
    expect(sub!.substitutedItemDataId).toBe(productB.id);

    // Confirm the substitution follow-up -> order PICKED.
    await confirmPick(page, h, sub!.id, Number(sub!.plannedAmount));
    const done = await getPickOrder(page, h, po.id);
    expect(done.state).toBe(PICKED);
    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(ORDER_PICKED);
  });

  test("13. Short pick -> PARTIAL_SHIP (uncovered remainder)", async ({
    authenticatedPage: page,
  }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s13");
    const product = await seedProduct(page, h, "s13");
    // Exactly 60 of A, no more A, no substitute -> the short is uncovered.
    await seedStockOnUL(page, h, { product, areas, amount: 60, prefix: "s13" });

    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 60 }],
      prefix: "s13",
    });
    await releaseOrder(page, h, order.id);
    const po = await releaseToPicking(page, h, order.id);

    // Short-confirm at 50 of 60; no cover source -> PARTIAL_SHIP accepts the short.
    await confirmPick(page, h, po.picks[0].id, 50);

    const done = await getPickOrder(page, h, po.id);
    expect(done.state).toBe(PICKED);
    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(ORDER_PICKED);
  });

  test("14. Picking guards", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s14");
    const product = await seedProduct(page, h, "s14");
    await seedStockOnUL(page, h, { product, areas, amount: 60, prefix: "s14" });

    // Already-in-picking order -> second release-to-picking 409.
    const order = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 60 }],
      prefix: "s14",
    });
    await releaseOrder(page, h, order.id);
    await releaseToPicking(page, h, order.id);
    const dup = await rawPost(page, "/api/v1/pick-orders", { deliveryOrderId: order.id }, h);
    expect(dup.status(), `dup release body: ${await dup.text()}`).toBe(409);

    // Non-PROCESSABLE order (created, never released) -> rejected 4xx.
    const fresh = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 5 }],
      prefix: "s14b",
    });
    const notReleased = await rawPost(
      page,
      "/api/v1/pick-orders",
      { deliveryOrderId: fresh.id },
      h,
    );
    expect(notReleased.status(), `non-processable body: ${await notReleased.text()}`).toBeGreaterThanOrEqual(400);
    expect(notReleased.status()).toBeLessThan(500);
  });

  // ====================================================================
  // Packing (outbound)
  // ====================================================================

  test("15. Open + pack", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s15");
    const { order } = await driveToPicked(page, h, { areas, amount: 60, prefix: "s15" });

    const opened = await openPacking(page, h, order.id);
    expect(opened.state).toBe(SHIP_PACKING);

    const packed = await packShipment(page, h, opened.id, 2.5);
    expect(packed.state).toBe(SHIP_PACKED);
    expect(packed.shippingUnits.length).toBeGreaterThanOrEqual(1);

    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(ORDER_PACKED);
  });

  test("16. Packing guards", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s16");

    // Open packing on a non-PICKED order -> 409.
    const product = await seedProduct(page, h, "s16");
    await seedStockOnUL(page, h, { product, areas, amount: 60, prefix: "s16" });
    const unpicked = await createOrder(page, h, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 60 }],
      prefix: "s16",
    });
    await releaseOrder(page, h, unpicked.id);
    const notPackable = await rawPost(
      page,
      "/api/v1/shipments",
      { deliveryOrderId: unpicked.id },
      h,
    );
    expect(notPackable.status(), `not-packable body: ${await notPackable.text()}`).toBe(409);

    // A PICKED order: open, then pack with weight <= 0 -> 422; second open -> 409.
    const { order } = await driveToPicked(page, h, { areas, amount: 60, prefix: "s16b" });
    const opened = await openPacking(page, h, order.id);

    const badWeight = await rawPost(
      page,
      `/api/v1/shipments/${opened.id}/pack`,
      { weight: 0 },
      h,
    );
    // weight<=0 is rejected. The plan guessed 422, but the guard is the DTO's
    // `@field:Positive` bean-validation -> RFC7807 validation-failed 400. Either
    // way the bad weight is refused (the capability under test); assert the real
    // 400 the app returns.
    expect(badWeight.status(), `bad-weight body: ${await badWeight.text()}`).toBe(400);

    const secondOpen = await rawPost(
      page,
      "/api/v1/shipments",
      { deliveryOrderId: order.id },
      h,
    );
    expect(secondOpen.status(), `second-open body: ${await secondOpen.text()}`).toBe(409);
  });

  // ====================================================================
  // Shipping (outbound)
  // ====================================================================

  test("17. Manifest -> dispatch -> SHIPPED -> order FINISHED", async ({
    authenticatedPage: page,
  }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s17");
    const { order, shipment } = await driveToPacked(page, h, { areas, amount: 60, prefix: "s17" });
    expect(shipment.state).toBe(SHIP_PACKED);

    const manifested = await post<Shipment>(
      page,
      `/api/v1/shipments/${shipment.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND", trackingNumber: "TRK-S17-12345" },
      h,
    );
    expect(manifested.state).toBe(SHIP_SHIPPING);
    expect(manifested.trackingNumber).toBe("TRK-S17-12345");

    const dispatched = await post<Shipment>(
      page,
      `/api/v1/shipments/${shipment.id}/dispatch`,
      {},
      h,
    );
    expect(dispatched.state).toBe(SHIP_SHIPPED);

    const ord = await getOrder(page, h, order.id);
    expect(ord.state).toBe(FINISHED);
  });

  test("18. Shipping guards + auto-tracking", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s18");

    // Manifest a non-PACKED shipment (still PACKING) -> 409.
    const { order } = await driveToPicked(page, h, { areas, amount: 60, prefix: "s18" });
    const opened = await openPacking(page, h, order.id); // PACKING(640)
    const earlyManifest = await rawPost(
      page,
      `/api/v1/shipments/${opened.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND" },
      h,
    );
    expect(earlyManifest.status(), `early-manifest body: ${await earlyManifest.text()}`).toBe(409);

    // Dispatch a non-SHIPPING shipment (PACKED, not yet manifested) -> 409.
    const packed = await packShipment(page, h, opened.id, 2.5); // PACKED(650)
    const earlyDispatch = await rawPost(
      page,
      `/api/v1/shipments/${packed.id}/dispatch`,
      {},
      h,
    );
    expect(earlyDispatch.status(), `early-dispatch body: ${await earlyDispatch.text()}`).toBe(409);

    // Manifest with no trackingNumber -> auto-generates a MAN-... tracking.
    const manifested = await post<Shipment>(
      page,
      `/api/v1/shipments/${packed.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND" },
      h,
    );
    expect(manifested.trackingNumber, "auto-tracking must be present").toBeTruthy();
    expect(manifested.trackingNumber!.startsWith("MAN-")).toBe(true);
  });

  // ====================================================================
  // Documents
  // ====================================================================

  test("19. Documents + availability gates", async ({ authenticatedPage: page }) => {
    const h: Headers = { Authorization: await captureAuthHeader(page) };
    const areas = await seedAreas(page, h, "s19");

    // First, a PACKED-not-yet-SHIPPING shipment: BOL must be gated 409.
    const gated = await driveToPacked(page, h, { areas, amount: 60, prefix: "s19gate" });
    const earlyBol = await rawGet(page, `/api/v1/shipments/${gated.shipment.id}/bol.pdf`, h);
    expect(earlyBol.status(), `gated-bol body: ${await earlyBol.text()}`).toBe(409);

    // Now a SHIPPING shipment: all three docs render.
    const ready = await driveToPacked(page, h, { areas, amount: 60, prefix: "s19doc" });
    const manifested = await post<Shipment>(
      page,
      `/api/v1/shipments/${ready.shipment.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND" },
      h,
    );
    expect(manifested.state).toBe(SHIP_SHIPPING);

    const bol = await rawGet(page, `/api/v1/shipments/${ready.shipment.id}/bol.pdf`, h);
    expect(bol.status()).toBe(200);
    expect(bol.headers()["content-type"]).toContain("application/pdf");
    expect((await bol.body()).subarray(0, 4).toString("latin1")).toBe("%PDF");

    const slip = await rawGet(page, `/api/v1/shipments/${ready.shipment.id}/packing-slip.pdf`, h);
    expect(slip.status()).toBe(200);
    expect(slip.headers()["content-type"]).toContain("application/pdf");
    expect((await slip.body()).subarray(0, 4).toString("latin1")).toBe("%PDF");

    const unitId = manifested.shippingUnits[0].id;
    const label = await rawGet(page, `/api/v1/shipping-units/${unitId}/label.zpl`, h);
    expect(label.status()).toBe(200);
    expect(label.headers()["content-type"]).toContain("text/plain");
    expect(await label.text()).toContain("^XA");
  });

  // (RBAC scenario 20 lives in its own describe block below so it can override credentials.)
});

// ====================================================================
// Security & RBAC (scenario 20) -- needs unauth + viewer + admin contexts
// ====================================================================

test.describe("v1.3 acceptance scenarios -- RBAC", () => {
  test.describe.configure({ timeout: 180_000 });

  test("20. Auth + role enforcement", async ({ authenticatedPage: page, browser }) => {
    // --- admin can read /shipments ---
    const adminAuth = await captureAuthHeader(page);
    const adminHeaders: Headers = { Authorization: adminAuth };
    const adminRead = await rawGet(page, "/api/v1/shipments", adminHeaders);
    expect(adminRead.status(), "admin must read /shipments").toBe(200);

    // --- unauthenticated GET /shipments -> 401 (fresh context, no Authorization) ---
    const anonCtx = await browser.newContext({ baseURL: page.url().split("/api")[0] || undefined });
    const baseURL = process.env.BASE_URL || "http://localhost";
    const anon = await anonCtx.request.get(`${baseURL}/api/v1/shipments`, {
      failOnStatusCode: false,
    });
    expect(anon.status(), `unauth body: ${await anon.text()}`).toBe(401);
    await anonCtx.close();

    // --- viewer (no fulfillment roles) POST /shipments/{id}/manifest -> 403 ---
    const viewerCtx = await browser.newContext();
    const viewerPage = await viewerCtx.newPage();
    await keycloakViewerLogin(viewerPage, baseURL);
    const viewerAuth = await captureAuthHeader(viewerPage);
    const viewerHeaders: Headers = { Authorization: viewerAuth };

    // viewer manifest on an arbitrary id must be rejected by role (403) BEFORE any
    // not-found/state check -- @RolesAllowed runs first.
    const viewerManifest = await viewerCtx.request.post(
      `${baseURL}/api/v1/shipments/999999/manifest`,
      {
        headers: viewerHeaders,
        data: { carrierName: "MANUAL", carrierService: "GROUND" },
        failOnStatusCode: false,
      },
    );
    expect(viewerManifest.status(), `viewer-manifest body: ${await viewerManifest.text()}`).toBe(403);

    // viewer also cannot read /shipments (no fulfillment-read).
    const viewerRead = await viewerCtx.request.get(`${baseURL}/api/v1/shipments`, {
      headers: viewerHeaders,
      failOnStatusCode: false,
    });
    expect(viewerRead.status(), `viewer-read body: ${await viewerRead.text()}`).toBe(403);

    await viewerCtx.close();
  });
});

/** Inline Keycloak login as the `viewer` user (mirrors fixtures/auth keycloakLogin). */
async function keycloakViewerLogin(
  page: import("@playwright/test").Page,
  baseURL: string,
): Promise<void> {
  // Tenant-selection screen was deleted (2026-07-18); Keycloak now redirects
  // straight to the login form (onLoad:'login-required').
  await page.goto(baseURL);
  await page.waitForSelector("#username", { timeout: 30_000 });
  await page.fill("#username", TEST_DATA.viewer.username);
  await page.fill("#password", TEST_DATA.viewer.password);
  await page.click("#kc-login");
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}
