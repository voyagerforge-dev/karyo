/**
 * Putaway & Tasks E2E -- v1.2 sub-phase 2.3 CAPSTONE (the closing inbound loop).
 *
 * Exercises the full receive -> auto-putaway -> move chain:
 *
 *  1. API-seed: product + a receiving (dock) location + 2 STORAGE locations
 *     (areas with usages ["STORAGE"], the location finder's eligibility gate).
 *  2. API: open a blind goods receipt, receive a line (50) at the dock. Receiving
 *     fires GoodsReceiptLineReceivedEvent which auto-creates a PUTAWAY task.
 *  3. Assert a PUTAWAY TransportOrder appeared in /tasks, RELEASED, with a
 *     finder-chosen suggested destination (one of the 2 seeded STORAGE locations).
 *  4. Through the Tasks UI: open the task -> Assign to me -> Start -> Complete
 *     (accept the suggested destination).
 *  5. Assert the task is FINISHED and the unit load physically MOVED: it now sits
 *     at the storage location (not the dock) -- verified via the Inventory page AND
 *     the stock-units API.
 *  6. Bonus: a QA-held received line produces NO putaway task.
 *
 * Re-runnable: unique e2e- names per run; global-setup sweeps transport_orders +
 * location_reservations by their e2e location names before deleting the locations.
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";

test.describe("Putaway & Tasks (closing loop)", () => {
  // Receiving creates stock, which UnitLoadService requires be owned by a real
  // goods owner (clientId != 0) -- the default `admin` user is the SYS tenant
  // (client_id 0) and 409s on the receive-line call. `manager` is client_id 1
  // (ACME) and holds order-write/inventory-write/task-write.
  test.use({ credentials: "manager" });

  test("receive -> auto-putaway -> assign -> start -> complete -> stock moved to storage", async ({
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
    type LocationEntity = { id: number; name: string };

    // --- 1. API setup: product + receiving (dock) + 2 STORAGE locations ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-put-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 1000,
    });

    // Dock (receiving) location — no STORAGE usage; this is the source.
    const dockArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-put-dock-area-${suffix}`,
      usages: ["GOODS_IN"],
    });
    const dockName = `e2e-put-dock-${suffix}`;
    const dock = await post<LocationEntity>(page, "/api/v1/locations", {
      name: dockName,
      scanCode: dockName,
      locationTypeId: locationType.id,
      areaId: dockArea.id,
    });

    // Two STORAGE locations — the finder picks one of them as the putaway dest.
    const storageArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-put-stor-area-${suffix}`,
      usages: ["STORAGE"],
    });
    const storage1Name = `e2e-put-str1-${suffix}`;
    const storage2Name = `e2e-put-str2-${suffix}`;
    // storage1/storage2 exercise multi-candidate creation; the finder ranks
    // ALL eligible STORAGE locations warehouse-wide (see the eligibility
    // check after the putaway task appears below), so these aren't asserted
    // to be the exact winner against a demo-seeded stack.
    await post<LocationEntity>(page, "/api/v1/locations", {
      name: storage1Name,
      scanCode: storage1Name,
      locationTypeId: locationType.id,
      areaId: storageArea.id,
    });
    await post<LocationEntity>(page, "/api/v1/locations", {
      name: storage2Name,
      scanCode: storage2Name,
      locationTypeId: locationType.id,
      areaId: storageArea.id,
    });

    const sku = `E2E-PUT-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-put-product-${suffix}`,
      description: "Putaway E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    // --- 2. API: blind receipt -> receive a line (50) at the dock ---
    // Receiving the line fires the putaway event (auto-creates the PUTAWAY task).

    const receipt = await post<Entity>(page, "/api/v1/goods-receipts", {
      carrierName: "E2E Putaway Carrier",
    });

    const ulLabel = `E2E-UL-PUT-${suffix}`;
    type ReceiveLineResult = { stockUnit: { unitLoadId: number }; unitLoadId?: number };
    const received = await post<ReceiveLineResult>(
      page,
      `/api/v1/goods-receipts/${receipt.id}/lines`,
      {
        itemDataId: product.id,
        amount: 50,
        locationId: dock.id,
        locationName: dockName,
        unitLoadLabel: ulLabel,
        allowOverReceipt: false,
      },
    );
    // ReceiveLineResponse nests the stock unit; pull the UL id from either shape.
    const unitLoadId =
      (received as any).stockUnit?.unitLoadId ?? (received as any).unitLoadId;
    expect(unitLoadId, "received line must carry a unit-load id").toBeTruthy();

    // The unit load currently sits at the dock.
    const ulAtDock = await get<{ storageLocationId: number; storageLocationName: string }>(
      page,
      `/api/v1/unit-loads/${unitLoadId}`,
    );
    expect(ulAtDock.storageLocationId).toBe(dock.id);

    // --- 3. The PUTAWAY task auto-appeared, RELEASED, with a suggested storage dest ---

    type Task = {
      id: number;
      orderNumber: string;
      transportType: string;
      unitLoadId: number;
      unitLoadLabel: string;
      stateName: string;
      suggestedLocationId: number | null;
      suggestedLocationName: string | null;
    };

    // Poll the task list until the AFTER_SUCCESS observer has created the task.
    let task: Task | undefined;
    await expect(async () => {
      const list = await get<{ content: Task[] }>(
        page,
        `/api/v1/transport-orders?type=PUTAWAY&state=100&page=0&size=50`,
      );
      task = list.content.find((t) => t.unitLoadLabel === ulLabel);
      expect(task, `putaway task for ${ulLabel} not found yet`).toBeTruthy();
    }).toPass({ timeout: 30_000 });

    expect(task!.transportType).toBe("PUTAWAY");
    expect(task!.stateName).toBe("RELEASED");
    expect(task!.suggestedLocationId, "finder must suggest a destination").toBeTruthy();

    // The finder ranks ALL eligible STORAGE locations warehouse-wide, emptiest
    // first (LocationFinderService docstring) -- not just this test's two
    // freshly-seeded ones. Against a shared/demo-seeded stack an existing
    // near-empty STORAGE location can legitimately outrank them, so assert
    // real eligibility (STORAGE-usage area, unlocked) via the API instead of
    // exact membership in storage1/storage2.
    const suggestedLoc = await get<{ lockType: number; area: { usages: string[] } }>(
      page,
      `/api/v1/locations/${task!.suggestedLocationId}`,
    );
    expect(suggestedLoc.area.usages).toContain("STORAGE");
    expect(suggestedLoc.lockType).toBe(0);
    const suggestedName = task!.suggestedLocationName!;
    const suggestedId = task!.suggestedLocationId!;

    // --- 4. Drive Assign -> Start -> Complete through the unified Tasks UI ---
    // (P3: /tasks is now a master-detail inbox across all 5 work types, no
    // drawer -- rows are `work-row-{ref}` cards, ref = "PUTAWAY:{taskId}".)

    await page.goto("/tasks");
    await expect(page.getByTestId("tasks-page")).toBeVisible();

    // Narrow the inbox with the MasterList search (also sidesteps the
    // list's row cap), then select the task by its deterministic ref.
    await page.getByPlaceholder("Search work…").fill(ulLabel);
    const taskRow = page.getByTestId(`work-row-PUTAWAY:${task!.id}`);
    await expect(taskRow).toBeVisible({ timeout: 15_000 });
    await taskRow.click();

    await expect(page.getByTestId("task-detail-state")).toBeVisible();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RELEASED");
    // The pane highlights the finder's suggested destination.
    await expect(page.getByTestId("task-suggested-destination")).toContainText(suggestedName);

    // SCREENSHOT 1: tasks inbox (pane open) showing the PUTAWAY task + suggested dest.
    await page.screenshot({ path: "/tmp/karyo-2-3-tasks-list.png", fullPage: true });

    // Assign to me -> RESERVED
    await page.getByTestId("task-assign-button").click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RESERVED", {
      timeout: 15_000,
    });

    // Start -> STARTED
    await page.getByTestId("task-start-button").click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("STARTED", {
      timeout: 15_000,
    });

    // Complete -> opens the confirm dialog with the location prefilled to the suggestion.
    await page.getByTestId("task-complete-button").click();
    const completeDialog = page.getByTestId("task-complete-dialog");
    await expect(completeDialog).toBeVisible();
    await expect(completeDialog).toContainText(suggestedName);

    // SCREENSHOT 2: complete dialog with the location picker prefilled to the suggestion.
    await page.screenshot({ path: "/tmp/karyo-2-3-task-complete.png", fullPage: true });

    // Accept the suggested destination.
    await page.getByTestId("task-complete-confirm").click();

    // FINISHED drops the task out of the claimable/claimed pool (see
    // TransportOrderRepository.findClaimable/findClaimedBy). Completing now also
    // invalidates the work-inbox queries (['work', ...], same as claim/release --
    // see use-tasks.ts's useInvalidateTasks), so once the refetch lands the row
    // leaves the list and the detail pane unmounts back to its empty state. Assert
    // that instead of a transient "FINISHED" text on a pane that's about to vanish
    // -- the API assertions below are the source of truth for the terminal state
    // and the resulting stock move.
    await expect(taskRow).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("task-detail-state")).not.toBeVisible();

    // --- 5. Assert the task FINISHED and the stock physically MOVED to storage ---

    // 5a. Task is FINISHED with the storage destination (API truth).
    const finishedTask = await get<{ stateName: string; destinationLocationId: number }>(
      page,
      `/api/v1/transport-orders/${task!.id}`,
    );
    expect(finishedTask.stateName).toBe("FINISHED");
    expect(finishedTask.destinationLocationId).toBe(suggestedId);

    // 5b. The unit load now lives at the storage location, NOT the dock.
    const ulAfter = await get<{ storageLocationId: number; storageLocationName: string }>(
      page,
      `/api/v1/unit-loads/${unitLoadId}`,
    );
    expect(ulAfter.storageLocationId).toBe(suggestedId);
    expect(ulAfter.storageLocationId).not.toBe(dock.id);

    // 5c. The Inventory page shows the stock at the storage location now.
    // (v3 master-detail rewrite: rows are MasterListRow cards keyed by SKU,
    // not <tr> elements -- see inventory-page.tsx.)
    await page.goto("/inventory");
    await expect(page.getByRole("heading", { name: "Inventory" })).toBeVisible();
    await page.getByPlaceholder("Search item, SKU, location, lot…").fill(sku);
    const invRow = page.getByTestId(`inv-row-${sku}`);
    await expect(invRow).toBeVisible({ timeout: 15_000 });
    await expect(invRow).toContainText(suggestedName);

    // --- 6. Bonus: a QA-held received line produces NO putaway task ---

    const qaReceipt = await post<Entity>(page, "/api/v1/goods-receipts", {
      carrierName: "E2E QA Carrier",
    });
    const qaLabel = `E2E-UL-PUTQ-${suffix}`;
    await post(page, `/api/v1/goods-receipts/${qaReceipt.id}/lines`, {
      itemDataId: product.id,
      amount: 10,
      locationId: dock.id,
      locationName: dockName,
      unitLoadLabel: qaLabel,
      lockType: 103,
      allowOverReceipt: false,
    });

    // Give the (non-)observer a beat, then assert no task references the QA-held UL.
    await page.waitForTimeout(2_000);
    const allTasks = await get<{ content: Task[] }>(
      page,
      `/api/v1/transport-orders?type=PUTAWAY&page=0&size=100`,
    );
    expect(allTasks.content.some((t) => t.unitLoadLabel === qaLabel)).toBe(false);
  });
});
