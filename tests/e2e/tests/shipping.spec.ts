/**
 * Shipping E2E -- v1.3 sub-phase 3.4-frontend (Shipment manifest + dispatch through the UI).
 *
 * Continues exactly where packing.spec.ts leaves off: drive an order all the way to a
 * PACKED shipment via the same API setup + UI release/confirm/pack steps, then ship it.
 *
 * Setup (mirrors packing.spec verbatim): seed a PACK_STAGING area + location, a STORAGE
 * area + location, a product, and ONE stock unit of 60. Then:
 *
 *  1. create an order for 60 and release it (reserves 60 -> PROCESSABLE)
 *  2. Orders UI: open the order detail drawer -> Release to picking
 *  3. Pick Orders UI: open the pick order -> confirm the pick at full qty (60)
 *     -> PickOrder PICKED; the delivery order advances to PICKED
 *  4. Packing UI: open the PICKED order -> Pack at weight 2.5 -> Shipment PACKED(650)
 *  5. Shipments UI: open the PACKED shipment -> the manifest form shows
 *  6. fill service GROUND (carrier left at default MANUAL) + Confirm manifest ->
 *     the shipment flips PACKED(650) -> SHIPPING(670): the Dispatch button + BOL/slip
 *     doc buttons appear
 *  7. Dispatch -> the shipment flips SHIPPING(670) -> SHIPPED(680): the SHIPPED badge
 *     shows, the doc buttons remain, and there is no longer a Dispatch button
 *
 * Carrier note: #manifest-carrier is a shadcn Select (Radix). Playwright can drive it,
 * but to keep this spec robust in headless we leave the carrier at its default MANUAL
 * (the catch-all ManualCarrierAdapter) and only fill #manifest-service.
 *
 * Unique e2e-ship-* names per run keep the spec re-runnable; the global-setup sweep
 * removes e2e-/E2E- prefixed artifacts from previous runs.
 *
 * NOTE: human-gated -- this spec PARSES/lists but is run by hand against the deployed
 * :8088 stack (binary PDF/ZPL downloads via the browser's `window.open`/anchor-click
 * path are out of scope for headless automation; we only assert those doc buttons are
 * present/enabled). Task 8 adds API-level asserts (via `page.request`, not the UI click
 * path) for the two new PDF documents -- packet-list and one shipping-unit's
 * content-list -- confirming they actually return real PDFs (200 + `%PDF` magic bytes),
 * fast and without a PDF parser.
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import {
  seedAreas,
  driveToPacked,
  get as apiGet,
  rawPost,
  post as apiPost,
  getOrder,
  type Shipment as ScenarioShipment,
} from "../fixtures/scenario-helpers";

const INCOMING = 100;
const ON_STOCK = 300;

test.describe("Shipping (UI flow)", () => {
  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // see the same fix in putaway.spec.ts / picking.spec.ts.
  test.use({ credentials: "manager" });

  test("pack an order, then manifest and dispatch the shipment", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(240_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };
    const suffix = Date.now();

    async function expectOk(res: APIResponse, label: string) {
      expect(res.ok(), `${label} failed: ${res.status()} ${await res.text()}`).toBe(true);
    }
    async function post<T>(p: Page, path: string, data: unknown): Promise<T> {
      const res = await p.request.post(path, { headers, data });
      await expectOk(res, `POST ${path}`);
      return (await res.json()) as T;
    }
    async function get<T>(p: Page, path: string): Promise<T> {
      const res = await p.request.get(path, { headers });
      await expectOk(res, `GET ${path}`);
      return (await res.json()) as T;
    }

    type Entity = { id: number };

    // --- API setup: one stock unit of one product, ON_STOCK at storage ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-ship-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });

    // PACK_STAGING area + location -- the pick container is created here.
    const packArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-ship-pack-${suffix}`,
      usages: ["PACK_STAGING"],
    });
    const packLocName = `e2e-ship-packloc-${suffix}`;
    await post(page, "/api/v1/locations", {
      name: packLocName,
      scanCode: packLocName,
      locationTypeId: locationType.id,
      areaId: packArea.id,
    });

    // SHIP_STAGING area + location -- the dispatch dock (UnitLoadMover moves the
    // container here; findShipStaging must resolve a ship dock or dispatch 409s).
    const shipArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-ship-stg-${suffix}`,
      usages: ["SHIP_STAGING"],
    });
    const shipLocName = `e2e-ship-stgloc-${suffix}`;
    await post(page, "/api/v1/locations", {
      name: shipLocName,
      scanCode: shipLocName,
      locationTypeId: locationType.id,
      areaId: shipArea.id,
    });

    // STORAGE area + location -- the source stock lives here.
    const storeArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-ship-store-${suffix}`,
      usages: ["STORAGE"],
    });
    const storeLocName = `e2e-ship-storeloc-${suffix}`;
    const storeLoc = await post<Entity>(page, "/api/v1/locations", {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    });

    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-ship-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-SHIP-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-ship-product-${suffix}`,
      description: "Shipping E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    // Single source unit = 60 (full-qty pick -> order reaches PICKED cleanly).
    const ul = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-SHIP-UL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 60,
      unitLoadId: ul.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    // Order for 60 -> release reserves 60 -> PROCESSABLE.
    const orderNumber = `e2e-ship-${suffix}`;
    const order = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber,
        customerName: "E2E Ship",
        lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 60 }],
      },
    );
    await post(page, `/api/v1/delivery-orders/${order.id}/release`, {});

    // --- UI: Orders -> open order detail -> Release to picking ---

    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();

    // Orders is also a master-detail inbox now (no drawer, no row testid) --
    // rows are unlabeled MasterListRow buttons, so select by their text.
    await page.getByPlaceholder(/search order/i).fill(order.orderNumber);
    const orderRow = page.locator("button").filter({ hasText: order.orderNumber });
    await expect(orderRow).toBeVisible({ timeout: 15_000 });
    await orderRow.click();
    await expect(page.getByRole("heading", { name: order.orderNumber })).toBeVisible();

    // Capture the POST /api/v1/pick-orders response so the new pick order's id
    // is known -- used below for a robust work-row selector (the listener
    // must attach before the click).
    const releaseResponsePromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === "/api/v1/pick-orders",
    );
    await page.getByTestId("release-to-picking-btn").click();
    const releaseResponse = await releaseResponsePromise;
    // Register row 8: the endpoint always returns a JSON array now
    // (releaseToPicking can mint more than one PickOrder via createTypeOrders) --
    // this order is a single line/single item type, so exactly one comes back.
    const releasedPickOrders = (await releaseResponse.json()) as {
      id: number;
      pickOrderNumber: string;
    }[];
    expect(releasedPickOrders.length).toBe(1);
    const pickOrder = releasedPickOrders[0];
    // Release-to-picking advances the order past PROCESSABLE, so the
    // "Release to picking" action itself unmounts (no drawer to hide).
    await expect(page.getByTestId("release-to-picking-btn")).toBeHidden({
      timeout: 15_000,
    });

    // --- UI: Tasks (PICK) -> select the pick work item -> confirm at full qty (60) ---
    // (P3: /pick-orders is retired -- pick work now lives in the unified
    // /tasks inbox; `?type=PICK` deep-links the type filter. Rows are
    // `work-row-{ref}` cards, ref = "PICK:{pickOrderId}".)

    await page.goto("/tasks?type=PICK");
    await expect(page.getByTestId("tasks-page")).toBeVisible();

    await page.getByPlaceholder("Search work…").fill(order.orderNumber);
    const pickRow = page.getByTestId(`work-row-PICK:${pickOrder.id}`);
    await expect(pickRow).toBeVisible({ timeout: 15_000 });
    await pickRow.click();
    await expect(page.getByTestId("picks-table")).toBeVisible();

    await page.locator('[data-testid^="pick-confirm-btn-"]').first().click();
    await expect(page.getByTestId("pick-confirm-form")).toBeVisible();
    await page.getByRole("button", { name: /confirm pick/i }).click();

    // PickOrder PICKED; the delivery order advances to PICKED. Confirming
    // the FINAL pick moves the PickOrder out of the claimable/claimed pool
    // (PickOrderRepository.findClaimable/findClaimedBy filter on
    // RELEASED/STARTED), and useConfirmPick invalidates ['work'] alongside
    // ['pick-orders']/['orders'] -- so the row leaves the /tasks inbox and
    // the detail pane unmounts (same "leaves the pool" pattern as
    // putaway.spec.ts). Assert that, then the pick-orders API for the
    // terminal state (not a transient pane state text).
    await expect(pickRow).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("picks-table")).not.toBeVisible();

    const pickOrderAfter = await get<{ state: number }>(
      page,
      `/api/v1/pick-orders/${pickOrder.id}`,
    );
    expect(pickOrderAfter.state).toBe(600);

    // --- UI: Packing -> select the PICKED order -> weigh -> confirm pack ---
    // /packing is master-detail (P4 recompose): rows are pack-btn-{pickOrderId}
    // buttons (see packing.spec.ts). Selecting the row triggers PackDetail's
    // open-packing effect (POST /api/v1/shipments) -- capture the response so
    // the new shipment's id is known for a robust row selector on /shipments.

    await page.goto("/packing");
    await expect(page.getByTestId("packing-page")).toBeVisible();

    const packBtn = page.getByTestId(`pack-btn-${pickOrder.id}`);
    await expect(packBtn).toBeVisible({ timeout: 15_000 });

    const openResponsePromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === "/api/v1/shipments",
    );
    await packBtn.click();
    const openResponse = await openResponsePromise;
    const shipment = (await openResponse.json()) as { id: number };

    await expect(page.getByTestId("pack-contents")).toBeVisible();
    await expect(page.getByTestId("pack-form")).toBeVisible({ timeout: 15_000 });

    await page.locator("#pack-weight").fill("2.5");
    await page.getByTestId("pack-confirm-btn").click();

    // Shipment flips PACKING(640) -> PACKED(650).
    await expect(page.getByTestId("packed-summary")).toBeVisible({
      timeout: 15_000,
    });

    // --- UI: Shipments -> open the PACKED shipment -> manifest it ---
    // /shipments is master-detail too (P4 recompose): rows are
    // shipment-row-{id} buttons; the "ship-drawer" Sheet is gone -- the
    // detail pane is the "ship-detail" container.

    await page.goto("/shipments");
    await expect(page.getByTestId("shipments-page")).toBeVisible();

    const shipRow = page.getByTestId(`shipment-row-${shipment.id}`);
    await expect(shipRow).toBeVisible({ timeout: 15_000 });
    await shipRow.click();

    const detail = page.getByTestId("ship-detail");
    await expect(detail).toBeVisible();
    // PACKED(650) -> the manifest form is shown.
    await expect(page.getByTestId("manifest-form")).toBeVisible({
      timeout: 15_000,
    });

    // Leave the carrier at its default (MANUAL) and only fill the service.
    await page.locator("#manifest-service").fill("GROUND");
    await page.getByTestId("manifest-confirm-btn").click();

    // --- The shipment flips PACKED(650) -> SHIPPING(670): Dispatch + docs appear ---
    // Scoped to the pane's "Dispatch" SectionCard heading, not a raw
    // state-name text match -- the pane's fixed subtitle ("Manifest,
    // dispatch and shipping documents.") already contains the word
    // "shipping" regardless of state, so a toContainText("SHIPPING") on the
    // whole pane would be a false positive even before the manifest.

    await expect(detail.getByRole("heading", { name: "Dispatch" })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId("dispatch-btn")).toBeVisible();
    await expect(page.getByTestId("dispatch-btn")).toBeEnabled();
    // Documents are available from SHIPPING onward (present + enabled, not fetched).
    await expect(page.getByTestId("doc-bol")).toBeVisible();
    await expect(page.getByTestId("doc-slip")).toBeVisible();
    await expect(page.getByTestId("doc-packet-list-btn")).toBeVisible();
    await expect(
      page.locator('[data-testid^="doc-label-"]').first(),
    ).toBeVisible();
    await expect(
      page.locator('[data-testid^="doc-content-list-btn-"]').first(),
    ).toBeVisible();

    // --- API-level document asserts (Task 8): the buttons above resolve to
    // real PDFs, not just present-in-the-DOM. Fast (magic-bytes-only, no PDF
    // parsing) per the brief -- fetch directly via page.request with the
    // captured auth header, same idiom as the `get`/`post` helpers above.

    const packetListRes = await page.request.get(
      `/api/v1/shipments/${shipment.id}/packet-list.pdf`,
      { headers },
    );
    expect(
      packetListRes.ok(),
      `packet-list.pdf failed: ${packetListRes.status()}`,
    ).toBe(true);
    const packetListBody = await packetListRes.body();
    expect(packetListBody.subarray(0, 4).toString("latin1")).toBe("%PDF");

    const shipmentAfterManifest = await get<{ shippingUnits: Entity[] }>(
      page,
      `/api/v1/shipments/${shipment.id}`,
    );
    expect(shipmentAfterManifest.shippingUnits.length).toBeGreaterThan(0);
    const shippingUnitId = shipmentAfterManifest.shippingUnits[0].id;

    const contentListRes = await page.request.get(
      `/api/v1/shipping-units/${shippingUnitId}/content-list.pdf`,
      { headers },
    );
    expect(
      contentListRes.ok(),
      `content-list.pdf failed: ${contentListRes.status()}`,
    ).toBe(true);
    const contentListBody = await contentListRes.body();
    expect(contentListBody.subarray(0, 4).toString("latin1")).toBe("%PDF");

    // --- Dispatch -> the shipment flips SHIPPING(670) -> SHIPPED(680) ---

    await page.getByTestId("dispatch-btn").click();

    // The pane's "Shipped" SectionCard heading shows; docs remain; the
    // Dispatch button is gone.
    await expect(detail.getByRole("heading", { name: "Shipped" })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId("doc-bol")).toBeVisible();
    await expect(page.getByTestId("dispatch-btn")).toHaveCount(0);
  });

  /**
   * Task 10: S3 lifecycle through the UI. Setup is API-only (scenario-helpers'
   * driveToPacked -- the API path is already exercised end-to-end by the test above,
   * so this one skips straight to a PACKED shipment) to keep the scenario focused on
   * claim/pause/resume/release. Manifest-while-paused is asserted at the API level
   * (409) per the brief -- the manifest form itself does not hide during a pause
   * (only the pack/manifest/dispatch service calls refuse), so a UI-only assertion
   * would not exercise the guard.
   */
  test("claim, pause and resume a packed shipment; manifest refuses while paused", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };

    const areas = await seedAreas(page, headers, "e2e-ship-lc");
    const { shipment } = await driveToPacked(page, headers, {
      areas,
      amount: 40,
      prefix: "e2e-ship-lc",
    });

    await page.goto("/shipments");
    await expect(page.getByTestId("shipments-page")).toBeVisible();
    const shipRow = page.getByTestId(`shipment-row-${shipment.id}`);
    await expect(shipRow).toBeVisible({ timeout: 15_000 });
    await shipRow.click();

    const detail = page.getByTestId("ship-detail");
    await expect(detail).toBeVisible();
    await expect(page.getByTestId("manifest-form")).toBeVisible({ timeout: 15_000 });

    // --- Claim: Claim button -> Release claim button; operatorId lands on the record. ---
    await expect(page.getByTestId("ship-claim-button")).toBeVisible();
    await page.getByTestId("ship-claim-button").click();
    await expect(page.getByTestId("ship-release-button")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("ship-claim-button")).toHaveCount(0);

    const claimed = await apiGet<ScenarioShipment & { operatorId: string | null }>(
      page,
      `/api/v1/shipments/${shipment.id}`,
      headers,
    );
    expect(claimed.operatorId).toBe(TEST_DATA.manager.username);

    // --- Pause: Pause button -> paused banner + Resume button; Pause button hides. ---
    await expect(page.getByTestId("ship-pause-button")).toBeVisible();
    await page.getByTestId("ship-pause-button").click();
    await expect(page.getByTestId("ship-paused-banner")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("ship-resume-button")).toBeVisible();
    await expect(page.getByTestId("ship-pause-button")).toHaveCount(0);

    // --- Manifest refuses (409) while paused, at the API level. ---
    const manifestWhilePaused = await rawPost(
      page,
      `/api/v1/shipments/${shipment.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND" },
      headers,
    );
    expect(manifestWhilePaused.status(), "manifest must 409 while paused").toBe(409);

    // --- Resume: banner clears, Pause button comes back. ---
    await page.getByTestId("ship-resume-button").click();
    await expect(page.getByTestId("ship-paused-banner")).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByTestId("ship-pause-button")).toBeVisible();
    await expect(page.getByTestId("ship-resume-button")).toHaveCount(0);

    // Manifest now succeeds (resumed, still owned by this operator, still PACKED).
    const manifestAfterResume = await rawPost(
      page,
      `/api/v1/shipments/${shipment.id}/manifest`,
      { carrierName: "MANUAL", carrierService: "GROUND" },
      headers,
    );
    expect(manifestAfterResume.ok(), "manifest must succeed once resumed").toBe(true);

    // --- Release claim: Release claim button -> Claim button comes back. ---
    // The manifest above flips the shipment to SHIPPING(670), which unmounts the
    // manifest form and re-renders the Dispatch pane -- the claim/release controls
    // stay in the top SectionCard regardless of state (< SHIPPED), so this still
    // exercises the same release path.
    await expect(page.getByTestId("ship-release-button")).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("ship-release-button").click();
    await expect(page.getByTestId("ship-claim-button")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("ship-release-button")).toHaveCount(0);

    const released = await apiGet<ScenarioShipment & { operatorId: string | null }>(
      page,
      `/api/v1/shipments/${shipment.id}`,
      headers,
    );
    expect(released.operatorId).toBeNull();
  });

  /**
   * Task 10: S4 cancel-then-repack. driveToPacked reaches PACKED via the pick ->
   * pack path (ShippingUnit.origin PACKOUT), so restoration lands on PICKED(600)
   * (not ON_STOCK) -- see ShippingLifecycleService.restoreUnit. Cancel is driven
   * through the UI (confirm dialog); the stock-state assertion and the re-pack call
   * are API-level, per the brief.
   *
   * CRITICAL 2 (final-review fix wave, outbound-completion sprint): cancel never
   * touches the DeliveryOrder's own state -- only the SHIPMENT regresses -- so the
   * order is STILL at PACKED(650) here. The re-pack call's completion therefore
   * hits OrderProgressionPort.markPacked while the order is already at its target
   * state; this is the exact repro for the DefaultOrderProgressionPort idempotency
   * fix (a naive strict re-transition throws and rolls back the whole re-pack,
   * including the shipment flip). Extended so this test's title -- "allows
   * re-packing" -- is actually true, not just implied by openPacking re-opening.
   */
  test("cancel a packed shipment restores stock to PICKED and allows re-packing", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };

    const areas = await seedAreas(page, headers, "e2e-ship-cancel");
    const { order, shipment } = await driveToPacked(page, headers, {
      areas,
      amount: 40,
      prefix: "e2e-ship-cancel",
    });

    const shipmentBefore = await apiGet<ScenarioShipment>(
      page,
      `/api/v1/shipments/${shipment.id}`,
      headers,
    );
    const unit = shipmentBefore.shippingUnits[0];
    expect(unit.unitLoadId, "packed unit must carry a unit-load id").toBeTruthy();

    const stockBefore = await apiGet<{ content: Array<{ state: number }> }>(
      page,
      `/api/v1/stock-units?unitLoadId=${unit.unitLoadId}`,
      headers,
    );
    expect(stockBefore.content[0].state).toBe(650); // PACKED

    await page.goto("/shipments");
    await expect(page.getByTestId("shipments-page")).toBeVisible();
    const shipRow = page.getByTestId(`shipment-row-${shipment.id}`);
    await expect(shipRow).toBeVisible({ timeout: 15_000 });
    await shipRow.click();

    const detail = page.getByTestId("ship-detail");
    await expect(detail).toBeVisible();
    await expect(page.getByTestId("ship-cancel-button")).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("ship-cancel-button").click();
    await expect(page.getByTestId("ship-cancel-confirm-btn")).toBeVisible();
    await page.getByTestId("ship-cancel-confirm-btn").click();

    await expect(
      detail.getByRole("heading", { name: "Shipment canceled" }),
    ).toBeVisible({ timeout: 15_000 });

    // Stock is restored to PICKED (origin PACKOUT), not ON_STOCK.
    const stockAfter = await apiGet<{ content: Array<{ state: number }> }>(
      page,
      `/api/v1/stock-units?unitLoadId=${unit.unitLoadId}`,
      headers,
    );
    expect(stockAfter.content[0].state).toBe(600); // PICKED

    const shipmentAfter = await apiGet<ScenarioShipment>(
      page,
      `/api/v1/shipments/${shipment.id}`,
      headers,
    );
    expect(shipmentAfter.state).toBe(800); // CANCELED

    // openPacking's duplicate-shipment guard ignores CANCELED shipments -- packing
    // re-opens for the same order.
    const reopened = await apiPost<ScenarioShipment>(
      page,
      "/api/v1/shipments",
      { deliveryOrderId: order.id },
      headers,
    );
    expect(reopened.id).not.toBe(shipment.id);
    expect(reopened.state).toBe(640); // PACKING

    // The order never left PACKED across the cancel -- repro the CRITICAL 2 hazard by
    // actually re-packing through to completion instead of stopping at the reopen.
    const orderStillPacked = await getOrder(page, headers, order.id);
    expect(orderStillPacked.state).toBe(650); // PACKED, untouched by the shipment cancel

    const repacked = await apiPost<ScenarioShipment>(
      page,
      `/api/v1/shipments/${reopened.id}/pack`,
      { weight: 2.5 },
      headers,
    );
    expect(repacked.state).toBe(650); // PACKED -- the re-pack completes cleanly

    const orderAfterRepack = await getOrder(page, headers, order.id);
    expect(orderAfterRepack.state).toBe(650); // still PACKED, no rollback
  });
});
