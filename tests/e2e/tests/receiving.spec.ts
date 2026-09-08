/**
 * Receiving E2E -- v1.2 sub-phase 2.2 (ASN -> goods receipt workbench),
 * rewritten against the master-detail UI (P2 Task 7).
 *
 * Self-seeds a product + a receiving location via the layout/product APIs
 * (workflow.spec.ts pattern), then drives the whole receive flow through the UI:
 *
 *  1. create an ASN with 2 lines (100 + 50) -> Release
 *  2. "Open receipt" -> jumps to the workbench bound to the ASN
 *  3. receive line 1 fully (100) -> its ASN line reads 100% progress / remaining 0
 *  4. receive line 2 partially (30) WITH a QUALITY FAULT lock
 *  5. attempt an over-receipt on line 2 (40 > remaining 20) -> inline confirm ->
 *     cancel it -> receive the correct 20 instead
 *  6. B7 segment: from /receiving, select the receipt row -> Claim -> workbench
 *     identifies the claiming user -> Pause -> workbench shows the paused banner and
 *     disables the receive form -> Resume -> banner clears
 *  7. finish the receipt -> non-QA stock ON_STOCK, QA-held stock stays INCOMING
 *  8. ASN detail: line 1 sits in a FINISHED chip
 *  9. Inventory page: per-unit states verified via API (the v3 grouped view
 *     only shows a lock-derived Hold badge, not raw per-unit state)
 *
 * ASN-auto-finish behaviour (verified against AsnService.recordReceipt): fully
 * receiving every line moves each *line* to FINISHED but leaves the *ASN* in
 * STARTED -- the ASN only reaches FINISHED through an explicit finish(). This
 * spec asserts exactly that (ASN stays STARTED with line 1 FINISHED).
 *
 * Unique e2e-rcv-* names per run keep the spec re-runnable; global-setup sweeps
 * e2e-* ASNs + their goods receipts (and the E2E-* stock they create).
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import {
  post,
  get,
  seedProduct,
  seedCountLocation,
  seedAsn,
  seedGoodsReceipt,
  receiveLine,
} from "../fixtures/scenario-helpers";

test.describe("Receiving (UI flow)", () => {
  // Receiving creates stock, which UnitLoadService requires be owned by a real
  // goods owner (clientId != 0) -- the default `admin` user is the SYS tenant
  // (client_id 0) and 409s on the very first receive-line call. `manager` is
  // client_id 1 (ACME), holds order-write/inventory-write, and can claim
  // receipts (MANAGER realm role), so the whole flow runs as manager.
  test.use({ credentials: "manager" });

  test("ASN -> release -> receive (full + QA + over-receipt) -> finish", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(300_000);

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

    // --- API setup: product + a receiving location (no demo coupling) ---
    // Product + item-unit lookup delegate to the shared seedProduct helper
    // (scenario-helpers) -- the location/zone/area shape here (no usage tag,
    // so it's NOT auto-putaway eligible, unlike the STORAGE location the
    // second test below needs) doesn't match any existing helper, so it stays
    // inline.

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-rcv-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });
    const zone = await post<Entity>(page, "/api/v1/zones", {
      name: `e2e-rcv-zone-${suffix}`,
      description: "Receiving E2E zone",
    });
    const area = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-rcv-area-${suffix}`,
      usages: [],
    });
    const receivingName = `e2e-rcv-loc-${suffix}`;
    await post<Entity>(page, "/api/v1/locations", {
      name: receivingName,
      scanCode: receivingName,
      locationTypeId: locationType.id,
      areaId: area.id,
      zoneId: zone.id,
    });

    const product = await seedProduct(page, headers, "e2e-rcv");
    const sku = product.number;

    const asnNumber = `e2e-rcv-${suffix}`;

    // --- 1. Create the ASN (2 lines: 100 + 50) through the UI ---

    await page.goto("/asns");
    await expect(page.getByRole("heading", { name: "ASNs" })).toBeVisible();

    // "Create ASN" entry point is the header's "New ASN" button; the form then
    // renders in the detail pane as a "Create ASN" section (asns-page.tsx / asn-form.tsx).
    await page.getByRole("button", { name: "New ASN" }).click();
    await expect(page.getByRole("heading", { name: "Create ASN" })).toBeVisible();

    await page.getByLabel("ASN #").fill(asnNumber);
    await page.getByLabel("Carrier").fill("E2E Carrier");

    // Line 1: product, expected 100
    await page.getByTestId("asn-line-0").getByPlaceholder("Search product...").fill(sku);
    await page.getByRole("option").filter({ hasText: sku }).first().click();
    await page.getByTestId("asn-line-0").getByLabel("Expected").fill("100");

    // Add a second line: same product, expected 50
    await page.getByRole("button", { name: "Add line" }).click();
    await page.getByTestId("asn-line-1").getByPlaceholder("Search product...").fill(sku);
    await page.getByRole("option").filter({ hasText: sku }).first().click();
    await page.getByTestId("asn-line-1").getByLabel("Expected").fill("50");

    await page.getByTestId("asn-form-submit").click();
    await expect(page.getByRole("heading", { name: "Create ASN" })).toBeHidden({
      timeout: 15_000,
    });

    // Open the ASN detail and release it. The drawer is gone -- a row click now
    // selects an inline detail pane on the right (MasterDetailLayout); the
    // MasterList search box placeholder is asns-page.tsx's own copy.
    const searchBox = () =>
      page.getByPlaceholder("Search ASN #, external #, carrier or supplier…");
    await searchBox().fill(asnNumber);
    const asnRow = page.getByText(asnNumber, { exact: true });
    await expect(asnRow).toBeVisible({ timeout: 15_000 });
    await asnRow.click();
    await expect(page.getByTestId("asn-detail-state")).toBeVisible();
    await expect(page.getByTestId("asn-detail-state")).toHaveText("CREATED");

    await page.getByRole("button", { name: "Release", exact: true }).click();
    await expect(page.getByTestId("asn-detail-state")).toHaveText("RELEASED", {
      timeout: 15_000,
    });

    // ASN line ids: the workbench's `receive-against-*`/`expected-line-*` testids
    // are id-based (Task 5 changed them from lineNumber-based), so resolve each
    // line's real id via the API right after release, before any receive call.
    const releasedAsn = await get<{
      lines: Array<{ id: number; lineNumber: number }>;
    }>(page, `/api/v1/asns?q=${encodeURIComponent(asnNumber)}&page=0&size=20`).then(
      (r: any) => r.content[0],
    );
    const lineId = (lineNumber: number) =>
      releasedAsn.lines.find((l: any) => l.lineNumber === lineNumber)!.id;

    // --- 2. Open receipt -> navigates to the workbench ---

    await page.getByTestId("asn-open-receipt-button").click();
    await expect(page.getByTestId("receiving-workbench")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("receipt-state")).toBeVisible();

    // Capture the receipt id from the workbench route (/receiving/{id}) so the
    // B7 segment below can find its row in the receiving list.
    const receiptId = Number(new URL(page.url()).pathname.split("/").pop());
    expect(receiptId, "receipt id parsed from workbench URL").toBeGreaterThan(0);

    // --- 3. Receive line 1 fully (100) ---

    async function receiveAgainst(lineNumber: number) {
      await page.getByTestId(`receive-against-${lineId(lineNumber)}`).click();
      // Product is locked, amount prefilled with remaining
      await expect(page.getByTestId("receive-locked-product")).toContainText(sku);
    }

    async function fillLocationAndReceive(amount: string, qaHold: boolean) {
      const loc = page.getByPlaceholder("Search location...");
      await loc.fill(receivingName.slice(0, 14));
      await page.getByRole("option").filter({ hasText: receivingName }).first().click();
      const amountBox = page.getByTestId("receive-amount");
      await amountBox.fill(amount);
      // Lock is a native <select> now (receive-form.tsx); 103 = QUALITY FAULT.
      if (qaHold) await page.getByTestId("receive-lock-select").selectOption("103");
      await page.getByTestId("receive-submit").click();
    }

    await receiveAgainst(1);
    await expect(page.getByTestId("receive-amount")).toHaveValue("100");
    await fillLocationAndReceive("100", false);

    // Received-lines table gains a row; QA column shows a dash for this line
    await expect(page.getByTestId("received-lines-table")).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByTestId("received-lines-table").getByText(sku).first(),
    ).toBeVisible();

    // Line 1's ASN line now reads 100% (remaining 0 -> it drops off the Expected
    // pane). Verify via the API to keep the assertion robust.
    const asnAfterLine1 = await get<{
      lines: Array<{ lineNumber: number; progressPercent: number; remainingAmount: number; stateName: string }>;
    }>(page, `/api/v1/asns?q=${encodeURIComponent(asnNumber)}&page=0&size=20`).then(
      (r: any) => r.content[0],
    );
    const line1 = asnAfterLine1.lines.find((l: any) => l.lineNumber === 1)!;
    expect(line1.progressPercent).toBe(100);
    expect(Number(line1.remainingAmount)).toBe(0);
    expect(line1.stateName).toBe("FINISHED");

    // --- 4. Receive line 2 partially (30) WITH a QUALITY FAULT lock ---

    await receiveAgainst(2);
    await expect(page.getByTestId("receive-amount")).toHaveValue("50");
    await fillLocationAndReceive("30", true);
    await expect(
      page.getByTestId("received-lines-table").getByText("QUALITY FAULT").first(),
    ).toBeVisible({ timeout: 15_000 });

    // --- 5. Over-receipt on line 2 (40 > remaining 20) -> confirm -> cancel -> 20 ---

    await receiveAgainst(2);
    // Remaining is now 20
    await expect(page.getByTestId("receive-amount")).toHaveValue("20");
    const loc = page.getByPlaceholder("Search location...");
    await loc.fill(receivingName.slice(0, 14));
    await page.getByRole("option").filter({ hasText: receivingName }).first().click();
    await page.getByTestId("receive-amount").fill("40");
    await page.getByTestId("receive-submit").click();

    // Inline over-receipt confirm appears
    const overConfirm = page.getByTestId("over-receipt-confirm");
    await expect(overConfirm).toBeVisible({ timeout: 15_000 });
    await expect(overConfirm).toContainText(/exceeds by 20/i);
    // Cancel it -> no line added
    await page.getByTestId("over-receipt-cancel").click();
    await expect(overConfirm).toBeHidden();

    // Receive the correct 20 instead
    await page.getByTestId("receive-amount").fill("20");
    await page.getByTestId("receive-submit").click();
    // The received-lines table should now hold 3 rows (line1 100, line2 30 QA, line2 20 QA)
    await expect(async () => {
      const rows = await page
        .getByTestId("received-lines-table")
        .getByRole("row")
        .count();
      expect(rows).toBeGreaterThanOrEqual(4); // header + 3 data rows
    }).toPass({ timeout: 15_000 });

    // --- 6. B7: claim / pause / resume via the receiving list + workbench ---

    await page.goto("/receiving");
    await expect(page.getByRole("heading", { name: "Receiving" })).toBeVisible();
    const receiptRow = page.getByTestId(`receipt-row-${receiptId}`);
    await expect(receiptRow).toBeVisible({ timeout: 15_000 });
    await receiptRow.click();

    // Claim: manager holds order-write and the receipt is unclaimed. The pane
    // only shows "Claimed by X" text for a claim held by *another* operator,
    // so the acting user's own claim is asserted via the Release-claim button
    // becoming available -- proof the claim landed on this session's user.
    await page.getByTestId("receipt-claim-button").click();
    await expect(page.getByTestId("receipt-release-button")).toBeVisible({ timeout: 15_000 });

    // Pause -> the Pause button drops away (canPause requires !pausedAt).
    await page.getByTestId("receipt-pause-button").click();
    await expect(page.getByTestId("receipt-pause-button")).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("receipt-paused-banner")).toBeVisible({ timeout: 15_000 });

    // Jump into the workbench and confirm the pause is enforced there too --
    // the header identifies the actual provisioned user, the banner blocks
    // receiving, and the receive-line submit button is disabled.
    await page.getByTestId("receipt-open-workbench").click();
    await expect(page.getByTestId("receiving-workbench")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(`Claimed by ${TEST_DATA.manager.username}`, { exact: true })).toBeVisible();
    await expect(page.getByTestId("workbench-paused-banner")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("receive-submit")).toBeDisabled();

    // Resume -> banner clears, receiving unblocked again.
    await page.getByTestId("workbench-resume-button").click();
    await expect(page.getByTestId("workbench-paused-banner")).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("receive-submit")).toBeEnabled();

    // --- 7. Finish the receipt ---

    await page.getByTestId("finish-receipt-button").click();
    await page.getByTestId("finish-receipt-confirm").click();
    await expect(page.getByTestId("receipt-state")).toHaveText("FINISHED", {
      timeout: 15_000,
    });

    // --- 8. ASN-auto-finish check + ASN detail line 1 FINISHED chip ---

    // The ASN stays STARTED (lines FINISHED, ASN NOT auto-finished by receiving).
    const asnState = await get<{ content: Array<{ stateName: string }> }>(
      page,
      `/api/v1/asns?q=${encodeURIComponent(asnNumber)}&page=0&size=20`,
    ).then((r) => r.content[0].stateName);
    expect(asnState).toBe("STARTED");

    await page.goto("/asns");
    await searchBox().fill(asnNumber);
    const row = page.getByText(asnNumber, { exact: true });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(page.getByTestId("asn-detail-state")).toBeVisible();
    await expect(
      page.getByTestId("asn-line-row-1").getByText("FINISHED", { exact: true }),
    ).toBeVisible();

    // --- 9. Non-QA stock ON_STOCK, QA-held stock stays INCOMING ---
    //
    // The v3 Inventory page (master-detail rewrite) groups stock units by
    // Item @ Location and only surfaces a lock-derived "Hold" status, not the
    // raw per-unit StockState -- since all three lines share the same
    // product+location they fold into a single group, so per-unit ON_STOCK
    // vs INCOMING is no longer distinguishable in that view (honest gap: see
    // inventory-rows.ts toItemLocationGroups). Assert the real per-unit
    // states via the API (itemDataId-scoped so demo noise can't interfere),
    // then sanity-check the aggregated group renders with a Hold badge.

    const unitsForSku = await get<{
      content: Array<{ amount: number; lockType: number; stateName: string }>;
    }>(page, `/api/v1/stock-units?itemDataId=${product.id}&page=0&size=20`).then((r) => r.content);
    expect(unitsForSku.length).toBe(3);
    const onStockUnits = unitsForSku.filter((u) => u.lockType === 0);
    const heldUnits = unitsForSku.filter((u) => u.lockType === 103);
    // Line 1 (100, unlocked) and the over-receipt-corrected leg of line 2 (20)
    // both land ON_STOCK -- the receive form remounts (key={formKey}) on every
    // "Receive against" click, so the lock select resets to None and the QA
    // hold from the first line-2 attempt (30) does NOT carry into the retry.
    expect(onStockUnits).toHaveLength(2);
    expect(onStockUnits.map((u) => u.amount).sort((a, b) => a - b)).toEqual([20, 100]);
    for (const u of onStockUnits) expect(u.stateName).toBe("ON_STOCK");
    expect(heldUnits).toHaveLength(1);
    expect(heldUnits[0].amount).toBe(30);
    expect(heldUnits[0].stateName).toBe("INCOMING");

    await page.goto("/inventory");
    await expect(page.getByRole("heading", { name: "Inventory" })).toBeVisible();
    await page.getByPlaceholder("Search item, SKU, location, lot…").fill(sku);

    const invRow = page.getByTestId(`inv-row-${sku}`);
    await expect(invRow).toBeVisible({ timeout: 15_000 });
    // The group carries at least one QUALITY-FAULT-locked constituent, so it
    // aggregates to "Hold" overall (classifyStatus in stock-status.ts).
    await expect(invRow.getByText("Hold", { exact: true })).toBeVisible();
    await invRow.click();
    await expect(page.getByTestId("inventory-detail")).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByTestId("inventory-detail").getByText("150", { exact: true }).first(),
    ).toBeVisible();
  });

  test("reverse a received line -> stock DELETABLE, putaway CANCELED (B3)", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

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

    // --- API setup: product + a STORAGE location (auto-putaway eligible) ---
    // Both delegate to shared scenario-helpers seeders -- this shape (one
    // location-type + one STORAGE area + one location, plus a product) is
    // exactly seedCountLocation's contract.

    const loc = await seedCountLocation(page, headers, {
      prefix: "e2e-rev",
      areaUsage: "STORAGE",
    });
    const location = { id: loc.locationId };
    const locName = loc.locationName;

    const product = await seedProduct(page, headers, "e2e-rev");
    const sku = product.number;

    // --- API: blind receipt -> receive a line (fires the auto-putaway event) ---

    const receipt = await post<Entity>(page, "/api/v1/goods-receipts", {
      carrierName: "E2E Reverse Carrier",
    });

    const ulLabel = `E2E-UL-REV-${suffix}`;
    type ReceiveLineResult = { lineId: number; stockUnitId: number; unitLoadId: number };
    const received = await post<ReceiveLineResult>(
      page,
      `/api/v1/goods-receipts/${receipt.id}/lines`,
      {
        itemDataId: product.id,
        amount: 15,
        locationId: location.id,
        locationName: locName,
        unitLoadLabel: ulLabel,
        allowOverReceipt: false,
      },
    );

    // Poll for the auto-created PUTAWAY task (same AFTER_SUCCESS observer as
    // putaway.spec.ts) -- reversal must cancel this task server-side.
    type Task = { id: number; unitLoadLabel: string; stateName: string };
    let task: Task | undefined;
    await expect(async () => {
      const list = await get<{ content: Task[] }>(
        page,
        `/api/v1/transport-orders?type=PUTAWAY&state=100&page=0&size=50`,
      );
      task = list.content.find((t) => t.unitLoadLabel === ulLabel);
      expect(task, `putaway task for ${ulLabel} not found yet`).toBeTruthy();
    }).toPass({ timeout: 30_000 });

    // --- Reverse the line through the UI (receipt detail's per-line button) ---

    await page.goto("/receiving");
    await expect(page.getByRole("heading", { name: "Receiving" })).toBeVisible();
    const receiptRow = page.getByTestId(`receipt-row-${receipt.id}`);
    await expect(receiptRow).toBeVisible({ timeout: 15_000 });
    await receiptRow.click();

    await expect(page.getByTestId("receipt-lines-table")).toBeVisible();
    const reverseBtn = page.getByTestId(`line-reverse-btn-${received.lineId}`);
    await expect(reverseBtn).toBeVisible({ timeout: 15_000 });
    await reverseBtn.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("Reverse line?");
    await dialog.getByRole("button", { name: "Reverse line" }).click();

    // The line flips to a "Reversed" pill (its reverse button disappears).
    await expect(page.getByTestId(`line-reversed-${received.lineId}`)).toBeVisible({
      timeout: 15_000,
    });
    await expect(reverseBtn).toBeHidden();

    // --- Assert the physical effects via the API ---

    // The stock unit is deleted (StockState.DELETABLE = 1000).
    const stockUnit = await get<{ state: number; stateName: string }>(
      page,
      `/api/v1/stock-units/${received.stockUnitId}`,
    );
    expect(stockUnit.stateName).toBe("DELETABLE");
    expect(stockUnit.state).toBe(1000);

    // The putaway task is canceled (OrderState.CANCELED = 800) in the same
    // transaction as the reversal -- same query pattern as putaway.spec.ts.
    const finishedTask = await get<{ state: number; stateName: string }>(
      page,
      `/api/v1/transport-orders/${task!.id}`,
    );
    expect(finishedTask.stateName).toBe("CANCELED");
    expect(finishedTask.state).toBe(800);
  });

  test("attach a second ASN to a receipt (M2M) -> receiving one line from each decrements both", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000);
    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };

    const dock = await seedCountLocation(page, headers, { prefix: "e2e-rcv-m2m" });
    const product = await seedProduct(page, headers, "e2e-rcv-m2m");

    const asnA = await seedAsn(page, headers, { product, amount: 20, prefix: "e2e-rcv-m2m-a" });
    const asnB = await seedAsn(page, headers, { product, amount: 30, prefix: "e2e-rcv-m2m-b" });

    // Open the receipt against ASN A only, then attach ASN B via the V424 M2M endpoint.
    const receipt = await seedGoodsReceipt(page, headers, {
      asnIds: [asnA.id],
      dockLocationId: dock.locationId,
      dockLocationName: dock.locationName,
      prefix: "e2e-rcv-m2m",
    });
    expect(receipt.asns.map((a) => a.id)).toEqual([asnA.id]);

    const attached = await post<{ asns: Array<{ id: number }> }>(
      page,
      `/api/v1/goods-receipts/${receipt.id}/asns`,
      { asnId: asnB.id },
      headers,
    );
    expect(attached.asns.map((a) => a.id).sort((x, y) => x - y)).toEqual(
      [asnA.id, asnB.id].sort((x, y) => x - y),
    );

    // Receive one line from each linked ASN.
    await receiveLine(page, headers, receipt.id, {
      asnLineId: asnA.lines[0].id,
      amount: 20,
      locationId: dock.locationId,
      locationName: dock.locationName,
    });
    await receiveLine(page, headers, receipt.id, {
      asnLineId: asnB.lines[0].id,
      amount: 30,
      locationId: dock.locationId,
      locationName: dock.locationName,
    });

    // Both ASNs' lines decremented to zero remaining.
    const asnAAfter = await get<{ lines: Array<{ remainingAmount: number }> }>(
      page,
      `/api/v1/asns/${asnA.id}`,
      headers,
    );
    const asnBAfter = await get<{ lines: Array<{ remainingAmount: number }> }>(
      page,
      `/api/v1/asns/${asnB.id}`,
      headers,
    );
    expect(Number(asnAAfter.lines[0].remainingAmount)).toBe(0);
    expect(Number(asnBAfter.lines[0].remainingAmount)).toBe(0);
  });

  test("a receipt bound to two ASNs renders both chips and the merged expected table in the web workbench", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000);
    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };

    const dock = await seedCountLocation(page, headers, { prefix: "e2e-rcv-multi" });
    const productA = await seedProduct(page, headers, "e2e-rcv-multi-a");
    const productB = await seedProduct(page, headers, "e2e-rcv-multi-b");
    const asnA = await seedAsn(page, headers, {
      product: productA,
      amount: 15,
      prefix: "e2e-rcv-multi-a",
    });
    const asnB = await seedAsn(page, headers, {
      product: productB,
      amount: 25,
      prefix: "e2e-rcv-multi-b",
    });
    const receipt = await seedGoodsReceipt(page, headers, {
      asnIds: [asnA.id, asnB.id],
      dockLocationId: dock.locationId,
      dockLocationName: dock.locationName,
      prefix: "e2e-rcv-multi",
    });

    await page.goto(`/receiving/${receipt.id}`);
    await expect(page.getByTestId("receiving-workbench")).toBeVisible({ timeout: 15_000 });

    // Both ASN chips/links render in the header.
    await expect(page.getByRole("link", { name: `ASN ${asnA.asnNumber}` })).toBeVisible();
    await expect(page.getByRole("link", { name: `ASN ${asnB.asnNumber}` })).toBeVisible();

    // Merged Expected table shows a line from each ASN (id-based testids, Task 5).
    const lineFromA = page.getByTestId(`expected-line-${asnA.lines[0].id}`);
    const lineFromB = page.getByTestId(`expected-line-${asnB.lines[0].id}`);
    await expect(lineFromA).toBeVisible({ timeout: 15_000 });
    await expect(lineFromB).toBeVisible();
    await expect(lineFromA).toContainText(asnA.asnNumber);
    await expect(lineFromB).toContainText(asnB.asnNumber);
    await expect(page.getByTestId(`receive-against-${asnA.lines[0].id}`)).toBeVisible();
    await expect(page.getByTestId(`receive-against-${asnB.lines[0].id}`)).toBeVisible();
  });

  test("UL pre-advice: registered via API, listed on the ASN detail, and printed on the ZPL sheet", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000);
    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };

    const product = await seedProduct(page, headers, "e2e-rcv-ula");
    const asn = await seedAsn(page, headers, { product, amount: 10, prefix: "e2e-rcv-ula" });

    const labelId = `e2e-ula-${Date.now()}`;
    const advice = await post<{ id: number; labelId: string }>(
      page,
      `/api/v1/asns/${asn.id}/ul-advices`,
      { labelId, itemDataId: product.id, expectedAmount: 5 },
      headers,
    );
    expect(advice.labelId).toBe(labelId);

    await page.goto("/asns");
    await expect(page.getByRole("heading", { name: "ASNs" })).toBeVisible();
    await page
      .getByPlaceholder("Search ASN #, external #, carrier or supplier…")
      .fill(asn.asnNumber);
    await page.getByText(asn.asnNumber, { exact: true }).click();
    await expect(page.getByTestId("asn-detail-state")).toBeVisible();

    const adviceRow = page.getByTestId(`ul-advice-row-${advice.id}`);
    await expect(adviceRow).toBeVisible({ timeout: 15_000 });
    await expect(adviceRow).toContainText(labelId);

    // ZPL pre-print sheet carries the advice's label id (API-level assert -- no
    // download-file assertion).
    const zplRes = await page.request.get(`/api/v1/asns/${asn.id}/ul-labels.zpl`, { headers });
    expect(zplRes.ok(), `GET ul-labels.zpl failed: ${zplRes.status()}`).toBe(true);
    const zpl = await zplRes.text();
    expect(zpl).toContain(labelId);
  });
});
