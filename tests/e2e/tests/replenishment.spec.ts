/**
 * Replenishment E2E — v1.3 Spec B1 (scan→task→complete restock loop).
 *
 * API-driven only (page.request.* with the captured bearer header). No live-stack
 * run is performed here; the actual `:8088` execution is a HUMAN GATE — the
 * maintainer deploys the branch and runs:
 *
 *   cd tests/e2e && BASE_URL=http://localhost:8088 \
 *     npx playwright test tests/replenishment.spec.ts --reporter=list
 *
 * Scenario steps:
 *   1. seed a product, a STORAGE area with TWO locations (pick-face + reserve).
 *   2. seed a reserve unit-load of the product at the reserve location (ON_STOCK).
 *   3. create a fix-assignment on the pick-face with minAmount=50 (face has 0 on-hand → below min).
 *   4. POST /replenishment/scan → assert generated.length === 1; task's fixAssignmentId matches.
 *   5. re-scan → generated.length === 0 (idempotent: open task blocks re-generation).
 *   6. fetch the REPLENISH transport order; drive assign → start → complete (API).
 *   7. assert the reserve UL is now at the pick-face location + task state is FINISHED.
 *   8. (negative) second fix-assignment with no reserve stock → scan puts it in shortfalls
 *      with reason === "NO_SOURCE".
 */
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import {
  type Headers,
  ON_STOCK,
  get,
  post,
  seedFixAssignment,
  seedProduct,
  uniq,
  type Entity,
} from "../fixtures/scenario-helpers";

// Transport-order states (com.karyo.tasks.vo.OrderState numeric codes).
const TASK_RELEASED = 100;
const TASK_RESERVED = 400;
const TASK_STARTED = 500;
const TASK_FINISHED = 700;

test.describe("Replenishment (API flow)", () => {
  test.describe.configure({ timeout: 180_000 });

  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // same fix as putaway.spec.ts / picking.spec.ts / packing.spec.ts.
  test.use({ credentials: "manager" });

  test(
    "replenishment: below-min face is restocked via scan→task→complete",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };
      const s = uniq("repl");

      // ── 1. Seed infrastructure ──────────────────────────────────────────
      // One STORAGE area, two locations: pick-face (empty) + reserve (has stock).

      const locationType = await post<Entity>(
        page,
        "/api/v1/location-types",
        {
          name: `${s}-shelf`,
          height: 200,
          width: 100,
          depth: 120,
          liftingCapacity: 500,
        },
        h,
      );

      const storageArea = await post<Entity>(
        page,
        "/api/v1/areas",
        { name: `${s}-store`, usages: ["STORAGE"] },
        h,
      );

      const faceName = `${s}-face`;
      const faceLoc = await post<Entity>(
        page,
        "/api/v1/locations",
        {
          name: faceName,
          scanCode: faceName,
          locationTypeId: locationType.id,
          areaId: storageArea.id,
        },
        h,
      );

      const reserveName = `${s}-reserve`;
      const reserveLoc = await post<Entity>(
        page,
        "/api/v1/locations",
        {
          name: reserveName,
          scanCode: reserveName,
          locationTypeId: locationType.id,
          areaId: storageArea.id,
        },
        h,
      );

      const unitLoadType = await post<Entity>(
        page,
        "/api/v1/unit-load-types",
        {
          name: `${s}-pallet`,
          height: 150,
          width: 120,
          depth: 100,
          weight: 25,
          liftingCapacity: 1500,
        },
        h,
      );

      // ── 2. Seed reserve stock ──────────────────────────────────────────
      // A unit-load at the reserve location with 100 units ON_STOCK.

      const product = await seedProduct(page, h, s);

      const reserveUl = await post<Entity>(
        page,
        "/api/v1/unit-loads",
        {
          clientId: 1,
          labelId: `${s.toUpperCase()}-RUL`,
          unitLoadTypeId: unitLoadType.id,
          storageLocationId: reserveLoc.id,
          storageLocationName: reserveName,
        },
        h,
      );

      const reserveStock = await post<Entity>(
        page,
        "/api/v1/stock-units",
        {
          itemDataId: product.id,
          itemDataNumber: product.number,
          amount: 100,
          unitLoadId: reserveUl.id,
          state: 100, // INCOMING
        },
        h,
      );
      // Advance to ON_STOCK (300).
      await post(
        page,
        `/api/v1/stock-units/${reserveStock.id}/change-state`,
        { state: ON_STOCK },
        h,
      );

      // ── 3. Fix-assignment on pick-face (minAmount=50, face on-hand=0) ──

      const fa = await seedFixAssignment(page, h, {
        locationId: faceLoc.id,
        itemDataId: product.id,
        minAmount: 50,
        desiredAmount: 100,
      });
      expect(fa.id, "fix-assignment must be created").toBeTruthy();

      // ── 4. POST /scan → one task generated, fixAssignmentId matches ────

      type ScanResult = {
        generated: Array<{
          taskId: number;
          orderNumber: string;
          fixAssignmentId: number;
          locationName: string;
          itemDataNumber: string | null;
          unitLoadId: number;
        }>;
        shortfalls: Array<{
          fixAssignmentId: number;
          locationName: string;
          itemDataNumber: string | null;
          currentAmount: number;
          minAmount: number | null;
          reason: string;
        }>;
      };

      const scan1 = await post<ScanResult>(
        page,
        "/api/v1/replenishment/scan",
        {},
        h,
      );
      const generated = scan1.generated.find(
        (t) => t.fixAssignmentId === fa.id,
      );
      expect(
        generated,
        "scan must generate a replenishment task for our fix-assignment",
      ).toBeTruthy();
      expect(
        generated!.unitLoadId,
        "generated task must carry the reserve UL",
      ).toBe(reserveUl.id);
      expect(
        scan1.shortfalls.find((sf) => sf.fixAssignmentId === fa.id),
        "our fix-assignment must not appear as a shortfall",
      ).toBeUndefined();

      const replenishTaskId = generated!.taskId;

      // ── 5. Re-scan → idempotent (open task blocks re-generation) ───────

      const scan2 = await post<ScanResult>(
        page,
        "/api/v1/replenishment/scan",
        {},
        h,
      );
      expect(
        scan2.generated.find((t) => t.fixAssignmentId === fa.id),
        "idempotency: no new task for our fix-assignment while an open task exists",
      ).toBeUndefined();

      // ── 6. Drive REPLENISH task: assign → start → complete ─────────────

      type TaskResp = {
        id: number;
        orderNumber: string;
        transportType: string;
        unitLoadId: number;
        state: number;
        stateName: string;
        destinationLocationId: number | null;
        destinationLocationName: string | null;
        suggestedLocationId: number | null;
        suggestedLocationName: string | null;
      };

      // Verify the task is RELEASED (state=100) and has the face as destination.
      const taskBefore = await get<TaskResp>(
        page,
        `/api/v1/transport-orders/${replenishTaskId}`,
        h,
      );
      expect(taskBefore.transportType).toBe("REPLENISH");
      expect(taskBefore.state).toBe(TASK_RELEASED);
      expect(taskBefore.unitLoadId).toBe(reserveUl.id);
      expect(taskBefore.destinationLocationId).toBe(faceLoc.id);

      // Assign to the supplied admin operator → RESERVED (200).
      const assigned = await post<TaskResp>(
        page,
        `/api/v1/transport-orders/${replenishTaskId}/assign`,
        { operatorId: TEST_DATA.admin.username },
        h,
      );
      expect(assigned.state).toBe(TASK_RESERVED);

      // Start → STARTED (400).
      const started = await post<TaskResp>(
        page,
        `/api/v1/transport-orders/${replenishTaskId}/start`,
        {},
        h,
      );
      expect(started.state).toBe(TASK_STARTED);

      // Complete (accept suggested destination = pick-face) → FINISHED (700).
      const completed = await post<TaskResp>(
        page,
        `/api/v1/transport-orders/${replenishTaskId}/complete`,
        {},
        h,
      );
      expect(completed.state).toBe(TASK_FINISHED);
      expect(completed.stateName).toBe("FINISHED");

      // ── 7. Assert reserve UL is now at pick-face + task FINISHED ────────

      const ulAfter = await get<{
        storageLocationId: number;
        storageLocationName: string;
      }>(page, `/api/v1/unit-loads/${reserveUl.id}`, h);
      expect(
        ulAfter.storageLocationId,
        "reserve UL must have moved to the pick-face location",
      ).toBe(faceLoc.id);
      expect(ulAfter.storageLocationName).toBe(faceName);

      // Confirm via API that the task record is FINISHED.
      const taskAfter = await get<TaskResp>(
        page,
        `/api/v1/transport-orders/${replenishTaskId}`,
        h,
      );
      expect(taskAfter.state).toBe(TASK_FINISHED);

      // ── 8. Negative: fix-assignment with no reserve stock → NO_SOURCE ──

      const product2 = await seedProduct(page, h, `${s}-nostock`);
      const emptyFaceName = `${s}-empty-face`;
      const emptyFaceLoc = await post<Entity>(
        page,
        "/api/v1/locations",
        {
          name: emptyFaceName,
          scanCode: emptyFaceName,
          locationTypeId: locationType.id,
          areaId: storageArea.id,
        },
        h,
      );
      const fa2 = await seedFixAssignment(page, h, {
        locationId: emptyFaceLoc.id,
        itemDataId: product2.id,
        minAmount: 10,
      });
      expect(fa2.id, "second fix-assignment must be created").toBeTruthy();

      const scan3 = await post<ScanResult>(
        page,
        "/api/v1/replenishment/scan",
        {},
        h,
      );
      // generated may include tasks for other fix-assignments in the DB;
      // we only care that THIS fix-assignment appears in shortfalls with NO_SOURCE.
      const shortfall = scan3.shortfalls.find(
        (sf) => sf.fixAssignmentId === fa2.id,
      );
      expect(
        shortfall,
        `fix-assignment ${fa2.id} must appear in shortfalls`,
      ).toBeTruthy();
      expect(shortfall!.reason).toBe("NO_SOURCE");
    },
  );
});
