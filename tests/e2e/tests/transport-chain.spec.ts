/**
 * Transport-chain + pause E2E — putaway-transport sprint (Task 7).
 *
 * Two independent scenarios:
 *
 *  (a) API-driven multi-hop chain: receive -> auto-PUTAWAY -> assign/start via API ->
 *      complete WITH AN OVERRIDE onto a transfer-staging location (not the finder's
 *      suggestion) -> ChainContinuationService mints a RELEASED TRANSFER successor that
 *      inherits the original suggestion as its own -> assign/start/complete the successor
 *      at that suggestion -> stock's unit load ends up at the real final target, and no
 *      third order is ever minted. Mirrors the fixture shape in the backend's
 *      TransferChainFlowTest (cluster + StorageArea(transferStaging=true) + a real
 *      location member of that cluster), but goes through the REAL receive -> auto-putaway
 *      path (not a direct-repository seed) so the location finder is genuinely exercised --
 *      see the inline note on why the staging location is deliberately kept OUT of STORAGE
 *      usage.
 *
 *  (b) Pause round-trip via the web UI, in two parts:
 *      - Part 1 drives one manual MOVE task: pause via `task-pause-button` through
 *        TransportPane (that's the UI action under test), verified via a race-free API poll
 *        for `pausedAt != null` rather than the `task-paused-banner` testid -- gate-confirmed
 *        structural race, see the inline comment at the assertion for the full trace. Resume
 *        goes through the API too (the UI has no reliable path back to a paused order once
 *        the board has dropped it -- a real, filed gap, not a test-authoring shortcut), then
 *        the board is reloaded, the task re-selected once it's back in the pool, and driven
 *        assign -> start -> complete -> FINISHED through the pane as normal. The
 *        `task-paused-banner` RENDER itself is covered at the unit level
 *        (`transport-pane.test.tsx`'s "shows the paused banner and Resume..." case), which
 *        mounts the pane directly with `pausedAt` already set and has no board/query race to
 *        contend with.
 *      - Part 2 seeds a second, throwaway MOVE purely to prove "paused orders leave
 *        findClaimable" deterministically: pause it (same API-poll verification as Part 1, for
 *        the same reason -- confirmed to race here too on re-verification, not just
 *        theoretically), then assert its board row disappears (`work-row-MOVE:{id}` hidden)
 *        with a real timeout -- no race, since this task is never resumed or re-selected
 *        afterward.
 *
 * Re-runnable: unique e2e- names per run (all `e2e-`/`E2E-` prefixed so the global-setup sweep
 * in fixtures/db-cleanup.ts picks them up, including the new PT15 storage-areas cleanup added
 * alongside this spec).
 */
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import {
  post,
  get,
  seedProduct,
  seedGoodsReceipt,
  receiveLine,
  seedAreas,
  seedTransferStagingArea,
  INCOMING,
  ON_STOCK,
  type Entity,
  type Headers,
} from "../fixtures/scenario-helpers";

type LocationEntity = { id: number; name: string };

/** Narrow projection of TransportOrderResponse -- just the fields this spec asserts on. */
type Task = {
  id: number;
  unitLoadLabel: string;
  transportType: string;
  stateName: string;
  sourceLocationId: number;
  sourceLocationName: string;
  suggestedLocationId: number | null;
  suggestedLocationName: string | null;
  successorId: number | null;
};

type WorkItem = { ref: string };

test.describe("Transport chains + pause (putaway-transport)", () => {
  test.use({ credentials: "manager" });

  // ── (a) API-driven multi-hop chain ────────────────────────────────────────

  test("receive -> auto-putaway -> complete onto transfer-staging -> TRANSFER successor -> final target, no third order", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(180_000);

    const authorization = await captureAuthHeader(page);
    const headers: Headers = { Authorization: authorization };
    const s = Date.now();

    // --- 1. Layout: location-type, dock (GOODS_IN), a normal STORAGE final-target
    //     location, and a transfer-staging cluster + StorageArea + location. ---

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-chn-shelf-${s}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 1000,
    }, headers);

    const dockArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-chn-dock-area-${s}`,
      usages: ["GOODS_IN"],
    }, headers);
    const dockName = `e2e-chn-dock-${s}`;
    const dock = await post<LocationEntity>(page, "/api/v1/locations", {
      name: dockName,
      scanCode: dockName,
      locationTypeId: locationType.id,
      areaId: dockArea.id,
    }, headers);

    // A normal STORAGE-eligible location so the finder has at least one guaranteed
    // candidate. Per putaway.spec's own caveat, the finder ranks ALL eligible STORAGE
    // locations warehouse-wide (emptiest first) -- against a shared/demo-seeded stack this
    // isn't guaranteed to be the exact winner, so the test captures whatever the finder
    // actually suggests below and asserts THAT survives the chain unchanged, which is the
    // real thing under test (not which specific location wins).
    const finalArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-chn-final-area-${s}`,
      usages: ["STORAGE"],
    }, headers);
    const finalLocName = `e2e-chn-final-${s}`;
    await post<LocationEntity>(page, "/api/v1/locations", {
      name: finalLocName,
      scanCode: finalLocName,
      locationTypeId: locationType.id,
      areaId: finalArea.id,
    }, headers);

    // Transfer-staging waypoint (see seedTransferStagingArea's KDoc for why the staging
    // location's own Area deliberately carries no usages -- this test drives the REAL
    // receive -> auto-putaway path, unlike the backend QuarkusTest it mirrors, which seeds
    // the predecessor directly).
    const staging = await seedTransferStagingArea(page, headers, {
      locationTypeId: locationType.id,
      prefix: `e2e-chn-stage-${s}`,
    });

    const product = await seedProduct(page, headers, "e2e-chn");

    // --- 2. Blind receipt -> receive a line at the dock -> auto-PUTAWAY task ---

    const receipt = await seedGoodsReceipt(page, headers, {
      carrierName: "E2E Chain Carrier",
      prefix: "e2e-chn",
    });
    const ulLabel = `E2E-CHN-UL-${s}`;
    const received = await receiveLine(page, headers, receipt.id, {
      itemDataId: product.id,
      amount: 20,
      locationId: dock.id,
      locationName: dockName,
      unitLoadLabel: ulLabel,
      allowOverReceipt: false,
    });
    expect(received.unitLoadId, "received line must carry a unit-load id").toBeTruthy();

    let predecessor: Task | undefined;
    await expect(async () => {
      const list = await get<{ content: Task[] }>(
        page,
        `/api/v1/transport-orders?type=PUTAWAY&state=100&page=0&size=50`,
        headers,
      );
      predecessor = list.content.find((t) => t.unitLoadLabel === ulLabel);
      expect(predecessor, `putaway task for ${ulLabel} not found yet`).toBeTruthy();
    }).toPass({ timeout: 30_000 });

    expect(predecessor!.stateName).toBe("RELEASED");
    expect(predecessor!.suggestedLocationId, "finder must suggest a destination").toBeTruthy();
    const finalTargetId = predecessor!.suggestedLocationId!;
    const finalTargetName = predecessor!.suggestedLocationName!;
    // The finder must not have handed us the staging location itself (see the note above).
    expect(finalTargetId).not.toBe(staging.locationId);

    // --- 3. Assign + start via API ---

    await post(page, `/api/v1/transport-orders/${predecessor!.id}/assign`, { operatorId: TEST_DATA.manager.username }, headers);
    await post(page, `/api/v1/transport-orders/${predecessor!.id}/start`, {}, headers);

    // --- 4. Complete WITH OVERRIDE to the staging location (not the finder's suggestion) ---

    const completedPredecessor = await post<Task>(
      page,
      `/api/v1/transport-orders/${predecessor!.id}/complete`,
      { destinationLocationId: staging.locationId, destinationLocationName: staging.locationName },
      headers,
    );
    expect(completedPredecessor.stateName).toBe("FINISHED");
    expect(
      completedPredecessor.successorId,
      "completing onto a transfer-staging location must mint a TRANSFER successor",
    ).toBeTruthy();

    // --- 5. Successor: RELEASED TRANSFER, inherits the original suggestion unchanged ---

    const successorList = await get<{ content: Task[] }>(
      page,
      `/api/v1/transport-orders?type=TRANSFER&q=${encodeURIComponent(ulLabel)}&page=0&size=50`,
      headers,
    );
    expect(successorList.content).toHaveLength(1);
    const successor = successorList.content[0];
    expect(successor.id).toBe(completedPredecessor.successorId);
    expect(successor.stateName).toBe("RELEASED");
    expect(successor.sourceLocationId).toBe(staging.locationId);
    expect(successor.sourceLocationName).toBe(staging.locationName);
    expect(successor.suggestedLocationId).toBe(finalTargetId);
    expect(successor.suggestedLocationName).toBe(finalTargetName);

    // Predecessor persisted as FINISHED with successorId set.
    const predecessorAfter = await get<Task>(page, `/api/v1/transport-orders/${predecessor!.id}`, headers);
    expect(predecessorAfter.stateName).toBe("FINISHED");
    expect(predecessorAfter.successorId).toBe(successor.id);

    // --- 6. Assign/start/complete the successor at its OWN suggestion (the real final target) ---

    await post(page, `/api/v1/transport-orders/${successor.id}/assign`, { operatorId: TEST_DATA.manager.username }, headers);
    await post(page, `/api/v1/transport-orders/${successor.id}/start`, {}, headers);
    const completedSuccessor = await post<Task>(
      page,
      `/api/v1/transport-orders/${successor.id}/complete`,
      {},
      headers,
    );
    expect(completedSuccessor.stateName).toBe("FINISHED");
    expect(
      completedSuccessor.successorId,
      "landing on the real final target must not chain again",
    ).toBeFalsy();

    // --- 7. Stock's unit load now sits at the final target (stock-units API) ---

    const stockUnitAfter = await get<{ locationId: number; locationName: string }>(
      page,
      `/api/v1/stock-units/${received.stockUnitId}`,
      headers,
    );
    expect(stockUnitAfter.locationId).toBe(finalTargetId);
    expect(stockUnitAfter.locationName).toBe(finalTargetName);

    // --- 8. No third order: exactly predecessor + successor exist for this unit load ---

    const family = await get<{ content: Task[] }>(
      page,
      `/api/v1/transport-orders?q=${encodeURIComponent(ulLabel)}&page=0&size=50`,
      headers,
    );
    expect(family.content).toHaveLength(2);
  });

  // ── (b) Pause round-trip via the web UI ───────────────────────────────────

  test("pause/resume round-trip through the tasks pane, then complete to FINISHED; a paused task leaves the available-work board", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

    const authorization = await captureAuthHeader(page);
    const headers: Headers = { Authorization: authorization };
    const areas = await seedAreas(page, headers, "e2e-pau");
    const s = Date.now();

    async function seedManualMove(prefix: string): Promise<{ order: Task; ulLabel: string }> {
      const destName = `${prefix}-dest-${s}`;
      const dest = await post<LocationEntity>(page, "/api/v1/locations", {
        name: destName,
        scanCode: destName,
        locationTypeId: areas.locationTypeId,
        areaId: areas.storeAreaId,
      }, headers);

      const ulLabel = `${prefix.toUpperCase()}-UL-${s}`;
      const ul = await post<Entity>(page, "/api/v1/unit-loads", {
        clientId: 1,
        labelId: ulLabel,
        unitLoadTypeId: areas.unitLoadTypeId,
        storageLocationId: areas.storeLocId,
        storageLocationName: areas.storeLocName,
      }, headers);

      const product = await seedProduct(page, headers, prefix);
      const stock = await post<Entity>(page, "/api/v1/stock-units", {
        itemDataId: product.id,
        itemDataNumber: product.number,
        amount: 5,
        unitLoadId: ul.id,
        state: INCOMING,
      }, headers);
      await post(page, `/api/v1/stock-units/${stock.id}/change-state`, { state: ON_STOCK }, headers);

      const order = await post<Task>(page, "/api/v1/transport-orders", {
        unitLoadId: ul.id,
        destinationLocationId: dest.id,
        destinationLocationName: destName,
      }, headers);
      return { order, ulLabel };
    }

    // --- Part 1: pause (via UI) -> API-verified pausedAt -> resume (via API) -> re-select
    //     once the task returns to the board -> assign -> start -> complete -> FINISHED ---

    const { order: order1, ulLabel: ulLabel1 } = await seedManualMove("e2e-pau1");

    await page.goto("/tasks");
    await expect(page.getByTestId("tasks-page")).toBeVisible();
    await page.getByPlaceholder("Search work…").fill(ulLabel1);
    const row1 = page.getByTestId(`work-row-MOVE:${order1.id}`);
    await expect(row1).toBeVisible({ timeout: 15_000 });
    await row1.click();

    await expect(page.getByTestId("task-detail-state")).toBeVisible();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RELEASED");

    // Pause needs no claim -- the button shows on an unclaimed RELEASED task, same row
    // that would otherwise offer "Assign to me".
    const pauseButton = page.getByTestId("task-pause-button");
    await expect(pauseButton).toBeVisible();
    await expect(page.getByTestId("task-assign-button")).toBeVisible();
    await pauseButton.click();

    // Deliberately NOT asserting task-paused-banner here (gate-confirmed structural race,
    // not CI-speed luck): usePauseTask's onSuccess invalidates BOTH the pane's own detail
    // query (['transport-orders','detail',id], which renders the banner) and the ['work']
    // queries backing this same master list, in the same handler, with no ordering
    // guarantee between the two refetches. A paused order drops out of
    // TransportOrderRepository.findClaimable/findClaimedBy, so the ['work'] refetch landing
    // first unmounts this pane (DetailEmptyState) before the banner ever renders -- exactly
    // what fired at the gate. The banner's RENDER is covered at the unit level instead
    // (transport-pane.test.tsx's "shows the paused banner and Resume..." case, which mounts
    // the pane directly with pausedAt already set -- no board/query race to contend with).
    // Poll the API instead: it proves the pause button's action landed without depending on
    // the pane still being mounted to observe it.
    await expect(async () => {
      const polled = await get<{ pausedAt: string | null }>(
        page,
        `/api/v1/transport-orders/${order1.id}`,
        headers,
      );
      expect(polled.pausedAt, "pause must land").not.toBeNull();
    }).toPass({ timeout: 10_000 });

    // Resume via API -- the UI has no reliable path back to a paused order once the board
    // has dropped it (no /tasks "paused" filter/deep-link, unlike receiving's
    // ReceiptFilter — see the task report's filed gap). Reload to force a fresh fetch of
    // the ['work'] queries (a direct API call bypasses the app's own React Query cache, so
    // nothing would otherwise trigger a refetch), then re-find the row -- it returns to
    // /work/available once pausedAt clears -- and re-select it.
    await post(page, `/api/v1/transport-orders/${order1.id}/resume`, {}, headers);
    const afterResume = await get<{ pausedAt: string | null; stateName: string }>(
      page,
      `/api/v1/transport-orders/${order1.id}`,
      headers,
    );
    expect(afterResume.pausedAt).toBeNull();
    expect(afterResume.stateName).toBe("RELEASED");

    await page.reload();
    await expect(page.getByTestId("tasks-page")).toBeVisible();
    await page.getByPlaceholder("Search work…").fill(ulLabel1);
    await expect(row1).toBeVisible({ timeout: 15_000 });
    await row1.click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RELEASED", { timeout: 15_000 });

    // Drive assign -> start -> complete through the pane to FINISHED.
    await page.getByTestId("task-assign-button").click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RESERVED", { timeout: 15_000 });

    await page.getByTestId("task-start-button").click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("STARTED", { timeout: 15_000 });

    await page.getByTestId("task-complete-button").click();
    const completeDialog = page.getByTestId("task-complete-dialog");
    await expect(completeDialog).toBeVisible();
    await page.getByTestId("task-complete-confirm").click();

    // FINISHED drops the task out of the work inbox entirely (same pattern as putaway.spec).
    await expect(row1).toBeHidden({ timeout: 15_000 });

    const finished = await get<{ stateName: string }>(page, `/api/v1/transport-orders/${order1.id}`, headers);
    expect(finished.stateName).toBe("FINISHED");

    // --- Part 2: a paused (throwaway) task deterministically leaves the available-work board ---

    const { order: order2, ulLabel: ulLabel2 } = await seedManualMove("e2e-pau2");

    // order2 was seeded via a raw API call, bypassing the app's own React Query cache (same
    // reason Part 1 reloads after its API-driven resume): the ['work'] queries backing this
    // board have been mounted since the initial page load and won't organically refetch just
    // because new data exists server-side. Reload to force a fresh fetch before searching.
    await page.reload();
    await expect(page.getByTestId("tasks-page")).toBeVisible();
    await page.getByPlaceholder("Search work…").fill(ulLabel2);
    const row2 = page.getByTestId(`work-row-MOVE:${order2.id}`);
    await expect(row2).toBeVisible({ timeout: 15_000 });
    await row2.click();
    await expect(page.getByTestId("task-detail-state")).toHaveText("RELEASED");

    await page.getByTestId("task-pause-button").click();

    // Same structural race as Part 1 (confirmed here too on re-verification, not just
    // theorized): NOT asserting task-paused-banner -- poll the API instead. See the longer
    // comment on Part 1's pause for the full trace of why this races.
    await expect(async () => {
      const polled = await get<{ pausedAt: string | null }>(
        page,
        `/api/v1/transport-orders/${order2.id}`,
        headers,
      );
      expect(polled.pausedAt, "pause must land").not.toBeNull();
    }).toPass({ timeout: 10_000 });

    // Never resumed/reselected from here -- free to wait for the real, non-racy outcome:
    // the board row for a paused task is gone (TransportOrderRepository.findClaimable
    // excludes pausedAt IS NOT NULL).
    await expect(row2).toBeHidden({ timeout: 15_000 });

    // API-level confirmation of the same fact, decoupled from the DOM/query-cache timing.
    const available = await get<WorkItem[]>(page, "/api/v1/work/available", headers);
    expect(available.some((w) => w.ref === `MOVE:${order2.id}`)).toBe(false);

    const order2State = await get<{ pausedAt: string | null; stateName: string }>(
      page,
      `/api/v1/transport-orders/${order2.id}`,
      headers,
    );
    expect(order2State.pausedAt).not.toBeNull();
    expect(order2State.stateName).toBe("RELEASED");
  });
});
