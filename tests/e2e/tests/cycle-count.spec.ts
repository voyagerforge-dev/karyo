/**
 * Cycle-count E2E — v1.3 Spec B2 (count → discrepancy → accept → adjust + unlock).
 *
 * API-driven only (page.request.* with the captured bearer header). No live-stack
 * run is performed here; the actual `:8088` execution is a HUMAN GATE — the
 * maintainer deploys the branch and runs:
 *
 *   cd tests/e2e && BASE_URL=http://localhost:8088 \
 *     npx playwright test tests/cycle-count.spec.ts --reporter=list
 *
 * RBAC: reuses `inventory-read` / `inventory-write` — no new realm roles, so the
 * live run does NOT need `--reset-db`.
 *
 * State codes confirmed against backend sources:
 *   CountOrderState: GENERATED=50, COUNTED=500, FINISHED=700, CANCELLED=800
 *   LockType (inventory): UNLOCKED=0, STOCKTAKING=7
 *
 * Scenario steps:
 *   1. (discrepancy path) seed product + STORAGE location + stock(amount=25); start session
 *      → assert one order GENERATED; assert stock lockType=7 (STOCKTAKING).
 *   2. GET /count-orders/{id}?view=entry → assert NO plannedAmount in response.
 *   3. POST /count-orders/{id}/count {lines:[{lineId, countedAmount:18}]} → COUNTED.
 *   4. POST /count-orders/{id}/accept → stock amount now 18, order FINISHED, lockType=0.
 *   5. (match path) second location + stock(amount=10); start session; count 10 → order
 *      auto-FINISHED (no accept needed), amount unchanged at 10, lockType=0.
 *   6. (recount path) third location + stock; start session; count mismatch → POST /recount
 *      → old order CANCELLED, a new GENERATED order exists for the same location.
 *
 * Task 9 additions (St3/St4/St7/St5 API surface):
 *   7. (location-empty path) stocked location; start session; POST .../location-empty on the
 *      GENERATED order with lines → order COUNTED (lines zeroed); POST .../accept → stock
 *      amount 0 ("gone" -- a soft DELETABLE, not a row delete, see StockCountingPort), stock +
 *      location lockType back to UNLOCKED (0), order FINISHED.
 *   8. (UL-missing path) location with TWO unit loads (two stock units); start session → order
 *      with 2 lines; POST .../unit-loads/missing {unitLoadId} zeroes that UL's line only (order
 *      state unchanged); POST .../count submits the remaining PLANNED line at its exact planned
 *      amount (matches -> FINISHED); POST .../accept applies the missing UL's zero + finishes
 *      the order -- the matched line's stock is untouched, the missing UL's stock is zeroed.
 *   9. (cancel path) stocked location; start session; POST .../cancel on the GENERATED order →
 *      CANCELLED, no replacement order (contrast recount), stock/location locks released, and
 *      since it was the session's only order the session auto-closes (CLOSED=700).
 *  10. (END_OF_PERIOD smoke) a dedicated location with NO stock at all; POST /count-sessions
 *      {type:"END_OF_PERIOD"} (no locationIds/areaId/pattern -- the scope is the whole tenant
 *      warehouse and is not caller-selectable) → session.type stamped; a zero-line GENERATED
 *      order exists for the known-empty location (among however many other locations the
 *      shared tenant DB has accumulated -- this test asserts ONLY its own location, never
 *      totals); POST .../location-empty finishes that order directly (zero-line branch).
 */
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import {
  type Headers,
  type Entity,
  type CountSessionView,
  type CountOrderView,
  type CountEntryView,
  get,
  post,
  rawPost,
  seedProduct,
  seedCountLocation,
  seedStockAtLocation,
  startCount,
} from "../fixtures/scenario-helpers";

// CountOrderState numeric codes (CountOrderState.kt).
const COUNT_GENERATED = 50;
const COUNT_COUNTED = 500;
const COUNT_FINISHED = 700;
const COUNT_CANCELLED = 800;

// CountSessionState numeric codes (CountSessionState.kt).
const SESSION_OPEN = 100;
const SESSION_CLOSED = 700;

// LockType numeric codes (inventory LockType.kt).
const LOCK_UNLOCKED = 0;
const LOCK_STOCKTAKING = 7;

type StockUnitResp = {
  id: number;
  amount: string | number;
  lockType: number;
  lockTypeName: string;
};

type LocationResp = {
  id: number;
  lockType: number;
  lockTypeName: string;
};

test.describe("Cycle Count (API flow)", () => {
  test.describe.configure({ timeout: 180_000 });

  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // same fix as putaway.spec.ts / picking.spec.ts / packing.spec.ts.
  test.use({ credentials: "manager" });

  test(
    "cycle-count: discrepancy → accept → amount adjusted + locks released",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      // ── 1. Seed: product + STORAGE location + stock(amount=25) ───────────

      const product = await seedProduct(page, h, "cnt-disc");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-disc" });

      const { stockUnitId } = await seedStockAtLocation(page, h, {
        product,
        locationId: loc.locationId,
        locationName: loc.locationName,
        locationTypeId: loc.locationTypeId,
        amount: 25,
        prefix: "cnt-disc",
      });

      // Start a cycle-count session for this single location.
      const session = await startCount(page, h, [loc.locationId]);

      expect(session.orders.length, "session must have exactly one order").toBe(
        1,
      );
      const order = session.orders[0];
      expect(
        order.state,
        "order must be GENERATED (50) after start",
      ).toBe(COUNT_GENERATED);
      expect(order.locationId).toBe(loc.locationId);
      expect(order.lines.length, "one stock unit → one count line").toBe(1);

      // Assert stock unit is now locked for counting (lockType 7 = STOCKTAKING).
      const stockLocked = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitId}`,
        h,
      );
      expect(
        stockLocked.lockType,
        "startCount must set stock lockType to STOCKTAKING (7)",
      ).toBe(LOCK_STOCKTAKING);

      // ── 2. GET ?view=entry → blind view has NO plannedAmount field ────────

      const entryView = await get<CountEntryView>(
        page,
        `/api/v1/count-orders/${order.id}?view=entry`,
        h,
      );
      expect(entryView.id).toBe(order.id);
      expect(entryView.lines.length).toBe(1);
      // CountEntryLine has lineId, itemDataNumber, lotNumber, serialNumber — no amount.
      const entryLine = entryView.lines[0];
      expect(
        (entryLine as Record<string, unknown>)["plannedAmount"],
        "blind entry view must NOT expose plannedAmount",
      ).toBeUndefined();
      expect(
        (entryLine as Record<string, unknown>)["countedAmount"],
        "blind entry view must NOT expose countedAmount",
      ).toBeUndefined();

      // ── 3. Submit count with discrepancy (counted 18, planned 25) ─────────

      const lineId = order.lines[0].id;
      const countedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/count`,
        { lines: [{ lineId, countedAmount: 18 }] },
        h,
      );
      expect(
        countedOrder.state,
        "order must be COUNTED (500) after submit with discrepancy",
      ).toBe(COUNT_COUNTED);
      // Line state should be COUNTED (not FINISHED) because 18 ≠ 25.
      const countedLine = countedOrder.lines.find((l) => l.id === lineId);
      expect(countedLine, "counted line must be present").toBeTruthy();
      // countedAmount is persisted.
      expect(Number(countedLine!.countedAmount)).toBe(18);

      // ── 4. Accept → stock adjusted to 18, order FINISHED, lockType=0 ─────

      const acceptedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/accept`,
        {},
        h,
      );
      expect(
        acceptedOrder.state,
        "order must be FINISHED (700) after accept",
      ).toBe(COUNT_FINISHED);

      const stockAfterAccept = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitId}`,
        h,
      );
      expect(
        Number(stockAfterAccept.amount),
        "stock amount must be adjusted to the counted value (18)",
      ).toBe(18);
      expect(
        stockAfterAccept.lockType,
        "accept must release stock lock back to UNLOCKED (0)",
      ).toBe(LOCK_UNLOCKED);
    },
  );

  test(
    "cycle-count: match path → order auto-FINISHED, amount unchanged",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      // Seed a second product + location + stock(amount=10).
      const product = await seedProduct(page, h, "cnt-match");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-match" });

      const { stockUnitId } = await seedStockAtLocation(page, h, {
        product,
        locationId: loc.locationId,
        locationName: loc.locationName,
        locationTypeId: loc.locationTypeId,
        amount: 10,
        prefix: "cnt-match",
      });

      const session = await startCount(page, h, [loc.locationId]);
      const order = session.orders[0];
      expect(order.state).toBe(COUNT_GENERATED);

      const lineId = order.lines[0].id;

      // Submit count matching planned amount exactly (10 == 10).
      const matchedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/count`,
        { lines: [{ lineId, countedAmount: 10 }] },
        h,
      );

      // All lines matched → submitCount calls finishOrder immediately → FINISHED.
      expect(
        matchedOrder.state,
        "order must be auto-FINISHED (700) when all lines match",
      ).toBe(COUNT_FINISHED);

      // Stock amount must remain 10 (no adjustment on match).
      const stockAfter = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitId}`,
        h,
      );
      expect(
        Number(stockAfter.amount),
        "amount must remain 10 on exact match",
      ).toBe(10);
      expect(
        stockAfter.lockType,
        "lock must be released to UNLOCKED (0) on auto-FINISHED",
      ).toBe(LOCK_UNLOCKED);
    },
  );

  test(
    "cycle-count: recount path → old order CANCELLED, fresh GENERATED order created",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      // Seed a third product + location + stock(amount=30).
      const product = await seedProduct(page, h, "cnt-recount");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-recount" });

      await seedStockAtLocation(page, h, {
        product,
        locationId: loc.locationId,
        locationName: loc.locationName,
        locationTypeId: loc.locationTypeId,
        amount: 30,
        prefix: "cnt-recount",
      });

      const session = await startCount(page, h, [loc.locationId]);
      const firstOrder = session.orders[0];
      expect(firstOrder.state).toBe(COUNT_GENERATED);

      const lineId = firstOrder.lines[0].id;

      // Submit a discrepancy (counted 20, planned 30).
      const countedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${firstOrder.id}/count`,
        { lines: [{ lineId, countedAmount: 20 }] },
        h,
      );
      expect(countedOrder.state).toBe(COUNT_COUNTED);

      // POST /recount → backend cancels old order + generates a new one.
      // The response is the NEW (fresh) CountOrderView.
      const freshOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${firstOrder.id}/recount`,
        {},
        h,
      );

      // The fresh order must be GENERATED (not CANCELLED) and have a NEW id.
      expect(
        freshOrder.state,
        "recount response must be the new GENERATED (50) order",
      ).toBe(COUNT_GENERATED);
      expect(
        freshOrder.id,
        "recount must create a new order (different id)",
      ).not.toBe(firstOrder.id);
      expect(
        freshOrder.locationId,
        "new order must be for the same location",
      ).toBe(loc.locationId);
      expect(
        freshOrder.lines.length,
        "fresh order must have one count line for the stock unit",
      ).toBe(1);

      // Verify the OLD order is now CANCELLED via the session read.
      const sessionAfter = await get<CountSessionView>(
        page,
        `/api/v1/count-sessions/${session.id}`,
        h,
      );
      const oldOrderInSession = sessionAfter.orders.find(
        (o) => o.id === firstOrder.id,
      );
      expect(
        oldOrderInSession,
        "cancelled order must still appear in the session",
      ).toBeTruthy();
      expect(
        oldOrderInSession!.state,
        "old order must be CANCELLED (800) after recount",
      ).toBe(COUNT_CANCELLED);

      // The fresh order also appears in the session.
      const newOrderInSession = sessionAfter.orders.find(
        (o) => o.id === freshOrder.id,
      );
      expect(
        newOrderInSession,
        "fresh order must appear in the session",
      ).toBeTruthy();
      expect(
        newOrderInSession!.state,
        "fresh order must be GENERATED (50) in the session",
      ).toBe(COUNT_GENERATED);
    },
  );

  test(
    "cycle-count: location-empty on a stocked location → COUNTED, accept → stock gone + lock released",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      const product = await seedProduct(page, h, "cnt-empty");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-empty" });

      const { stockUnitId } = await seedStockAtLocation(page, h, {
        product,
        locationId: loc.locationId,
        locationName: loc.locationName,
        locationTypeId: loc.locationTypeId,
        amount: 25,
        prefix: "cnt-empty",
      });

      const session = await startCount(page, h, [loc.locationId]);
      const order = session.orders[0];
      expect(order.state).toBe(COUNT_GENERATED);
      expect(order.lines.length, "location holds one stock unit").toBe(1);

      // Order has lines -> branch 2: zero every PLANNED line, advance to COUNTED for review.
      const emptiedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/location-empty`,
        {},
        h,
      );
      expect(
        emptiedOrder.state,
        "location-empty on a lined order must land in COUNTED (500) for review",
      ).toBe(COUNT_COUNTED);
      expect(
        Number(emptiedOrder.lines[0].countedAmount),
        "location-empty must zero the line's countedAmount",
      ).toBe(0);

      // Manager accepts the zero -> stock adjusted to 0 ("gone"), order FINISHED, locks released.
      const acceptedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/accept`,
        {},
        h,
      );
      expect(
        acceptedOrder.state,
        "accept must FINISH (700) the order",
      ).toBe(COUNT_FINISHED);

      const stockAfter = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitId}`,
        h,
      );
      expect(
        Number(stockAfter.amount),
        "accepted zero-count must drive stock amount to 0 (gone)",
      ).toBe(0);
      expect(
        stockAfter.lockType,
        "accept must release the stock lock back to UNLOCKED (0)",
      ).toBe(LOCK_UNLOCKED);

      const locAfter = await get<LocationResp>(
        page,
        `/api/v1/locations/${loc.locationId}`,
        h,
      );
      expect(
        locAfter.lockType,
        "accept must release the location lock back to UNLOCKED (0)",
      ).toBe(LOCK_UNLOCKED);
    },
  );

  test(
    "cycle-count: UL-missing zeroes that UL's lines, remaining line countable, accept applies both",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      const product = await seedProduct(page, h, "cnt-ulmiss");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-ulmiss" });

      // Two unit loads at the same location -> two count lines on one order.
      const { stockUnitId: stockUnitMissingId, unitLoadId: unitLoadMissingId } =
        await seedStockAtLocation(page, h, {
          product,
          locationId: loc.locationId,
          locationName: loc.locationName,
          locationTypeId: loc.locationTypeId,
          amount: 15,
          prefix: "cnt-ulmiss-a",
        });
      const { stockUnitId: stockUnitPresentId, unitLoadId: unitLoadPresentId } =
        await seedStockAtLocation(page, h, {
          product,
          locationId: loc.locationId,
          locationName: loc.locationName,
          locationTypeId: loc.locationTypeId,
          amount: 20,
          prefix: "cnt-ulmiss-b",
        });

      const session = await startCount(page, h, [loc.locationId]);
      const order = session.orders[0];
      expect(order.state).toBe(COUNT_GENERATED);
      expect(order.lines.length, "two unit loads -> two count lines").toBe(2);

      const missingLine = order.lines.find(
        (l) => l.unitLoadId === unitLoadMissingId,
      );
      const presentLine = order.lines.find(
        (l) => l.unitLoadId === unitLoadPresentId,
      );
      expect(missingLine, "missing UL must have its own line").toBeTruthy();
      expect(presentLine, "present UL must have its own line").toBeTruthy();

      // Report the first UL missing -> only its (still-PLANNED) line is zeroed; order state
      // is deliberately left unchanged so the operator can keep counting the rest.
      const entryAfterMissing = await post<CountEntryView>(
        page,
        `/api/v1/count-orders/${order.id}/unit-loads/missing`,
        { unitLoadId: unitLoadMissingId },
        h,
      );
      const entryMissingLine = entryAfterMissing.lines.find(
        (l) => l.unitLoadId === unitLoadMissingId,
      );
      const entryPresentLine = entryAfterMissing.lines.find(
        (l) => l.unitLoadId === unitLoadPresentId,
      );
      expect(
        entryMissingLine?.counted,
        "unit-loads/missing must flag the missing UL's line as counted",
      ).toBe(true);
      expect(
        entryPresentLine?.counted,
        "the other line must remain uncounted, still awaiting operator input",
      ).toBe(false);

      // Submit the remaining line at its exact planned amount (a match).
      const countedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/count`,
        { lines: [{ lineId: presentLine!.id, countedAmount: 20 }] },
        h,
      );
      // The missing-UL line mismatches its (non-zero) plan -> allMatched is false overall,
      // so the order lands in COUNTED (500) for review, not an auto-FINISHED.
      expect(
        countedOrder.state,
        "one line mismatched (missing UL) -> order awaits review",
      ).toBe(COUNT_COUNTED);
      const countedPresentLine = countedOrder.lines.find(
        (l) => l.id === presentLine!.id,
      );
      expect(Number(countedPresentLine!.countedAmount)).toBe(20);

      // Accept applies the missing UL's zero-adjustment and finishes the order.
      const acceptedOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/accept`,
        {},
        h,
      );
      expect(acceptedOrder.state).toBe(COUNT_FINISHED);

      const missingStockAfter = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitMissingId}`,
        h,
      );
      expect(
        Number(missingStockAfter.amount),
        "missing UL's stock must be zeroed by accept",
      ).toBe(0);
      expect(missingStockAfter.lockType).toBe(LOCK_UNLOCKED);

      const presentStockAfter = await get<StockUnitResp>(
        page,
        `/api/v1/stock-units/${stockUnitPresentId}`,
        h,
      );
      expect(
        Number(presentStockAfter.amount),
        "matched line's stock amount must be untouched",
      ).toBe(20);
      expect(presentStockAfter.lockType).toBe(LOCK_UNLOCKED);
    },
  );

  test(
    "cycle-count: cancel a GENERATED order → CANCELLED, no replacement, session closes",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      const product = await seedProduct(page, h, "cnt-cancel");
      const loc = await seedCountLocation(page, h, { prefix: "cnt-cancel" });

      await seedStockAtLocation(page, h, {
        product,
        locationId: loc.locationId,
        locationName: loc.locationName,
        locationTypeId: loc.locationTypeId,
        amount: 12,
        prefix: "cnt-cancel",
      });

      const session = await startCount(page, h, [loc.locationId]);
      expect(session.state, "session must open OPEN (100)").toBe(SESSION_OPEN);
      const order = session.orders[0];
      expect(order.state).toBe(COUNT_GENERATED);

      const cancelledOrder = await post<CountOrderView>(
        page,
        `/api/v1/count-orders/${order.id}/cancel`,
        {},
        h,
      );
      expect(
        cancelledOrder.state,
        "cancel must land the order in CANCELLED (800)",
      ).toBe(COUNT_CANCELLED);

      // No replacement order (contrast recount): the session's only order stays this one.
      const sessionAfter = await get<CountSessionView>(
        page,
        `/api/v1/count-sessions/${session.id}`,
        h,
      );
      expect(
        sessionAfter.orders.length,
        "cancel must not generate a replacement order",
      ).toBe(1);
      expect(sessionAfter.orders[0].id).toBe(order.id);
      expect(sessionAfter.orders[0].state).toBe(COUNT_CANCELLED);

      // The cancelled order was the session's only (now-terminal) order -> auto-close.
      expect(
        sessionAfter.state,
        "session must auto-close once its only order is terminal",
      ).toBe(SESSION_CLOSED);

      // Locks released; no count applied, so the stock amount is untouched.
      const locAfter = await get<LocationResp>(
        page,
        `/api/v1/locations/${loc.locationId}`,
        h,
      );
      expect(
        locAfter.lockType,
        "cancel must release the location lock back to UNLOCKED (0)",
      ).toBe(LOCK_UNLOCKED);
    },
  );

  test(
    "cycle-count: END_OF_PERIOD smoke → session type stamped, zero-line order for an empty location, location-empty finishes it",
    async ({ authenticatedPage: page }) => {
      const h: Headers = { Authorization: await captureAuthHeader(page) };

      // A dedicated location with NO stock at all -- END_OF_PERIOD must generate a zero-line
      // order for it (branch 1 of locationEmpty), not skip it (skipping is reserved for
      // reserved-stock / already-locked locations).
      const emptyLoc = await seedCountLocation(page, h, { prefix: "cnt-eop" });

      // END_OF_PERIOD owns its own scope (the whole tenant warehouse) -- locationIds/areaId/
      // locationNamePattern/scopeStrategy must be omitted, not merely empty-valued, per
      // requireNoCallerScope. Bypass the startCount() helper (which always sends locationIds)
      // and post the type-only body directly.
      //
      // THIS START FREEZES THE WHOLE SHARED TENANT WAREHOUSE: every location the suite has ever
      // seeded gets a GENERATED order and a STOCKTAKING lock. Only this test's own order is
      // finished by the assertions below, so without the finally-block cleanup every OTHER spec
      // (and any later run against the same durable DB) would be counting against frozen stock.
      // The cleanup cancels every order this session opened except our own. The session id is
      // captured from the POST response ALONE (startedSessionId, set before the follow-up GET
      // below runs) -- if that GET throws, the freeze must still be found and thawed, not left
      // stranded because the full-graph fetch never got as far as populating `session`.
      let startedSessionId: number | undefined;
      let session: CountSessionView | undefined;
      try {
        // POST /count-sessions now returns the summary projection (no nested orders --
        // defect-burndown row 8) -- a follow-up GET /count-sessions/{id} fetches the full
        // graph the rest of this test (and the finally-block cleanup) needs.
        const started = await post<{ id: number; type: string }>(
          page,
          "/api/v1/count-sessions",
          { type: "END_OF_PERIOD" },
          h,
        );
        startedSessionId = started.id;

        expect(
          started.type,
          "session.type must be stamped END_OF_PERIOD",
        ).toBe("END_OF_PERIOD");

        session = await get<CountSessionView>(
          page,
          `/api/v1/count-sessions/${started.id}`,
          h,
        );

        // The tenant DB is shared across the whole suite -- assert only OUR location's order,
        // never a total order/skip count.
        const ourOrder = session.orders.find(
          (o) => o.locationId === emptyLoc.locationId,
        );
        expect(
          ourOrder,
          "END_OF_PERIOD must generate an order for the known-empty location",
        ).toBeTruthy();
        expect(
          ourOrder!.state,
          "zero-line order must still be GENERATED, awaiting location-empty",
        ).toBe(COUNT_GENERATED);
        expect(
          ourOrder!.lines.length,
          "the empty location's order must have no lines",
        ).toBe(0);

        // Zero-line branch of locationEmpty: finishes the order directly, no COUNTED hop.
        const finishedOrder = await post<CountOrderView>(
          page,
          `/api/v1/count-orders/${ourOrder!.id}/location-empty`,
          {},
          h,
        );
        expect(
          finishedOrder.state,
          "location-empty on a zero-line order must FINISH (700) it directly",
        ).toBe(COUNT_FINISHED);

        const locAfter = await get<LocationResp>(
          page,
          `/api/v1/locations/${emptyLoc.locationId}`,
          h,
        );
        expect(
          locAfter.lockType,
          "finishing must release the location lock back to UNLOCKED (0)",
        ).toBe(LOCK_UNLOCKED);
      } finally {
        // Thaw the rest of the warehouse. Cancel releases the order's stock + location locks
        // (St7) and leaves no replacement order. rawPost so a race (an order already terminal,
        // or cancelled by a retry of this test) can never mask the real failure above.
        //
        // Prefer the order graph already fetched above (`session`); if the primary GET never
        // completed (threw before `session` was assigned), fall back to a fresh GET keyed off
        // `startedSessionId` -- the one thing guaranteed to have survived the POST. That fallback
        // GET is itself best-effort (`.catch`): if it also fails, there is nothing more this test
        // can do to self-heal, but it must not throw here and mask the real failure above.
        const ordersToCancel =
          session?.orders ??
          (startedSessionId !== undefined
            ? (
                await get<CountSessionView>(
                  page,
                  `/api/v1/count-sessions/${startedSessionId}`,
                  h,
                ).catch(() => undefined)
              )?.orders ?? []
            : []);
        for (const o of ordersToCancel) {
          if (o.locationId === emptyLoc.locationId) continue; // ours -- finished above
          if (o.state !== COUNT_GENERATED) continue; // already terminal
          await rawPost(page, `/api/v1/count-orders/${o.id}/cancel`, {}, h);
        }
      }
    },
  );
});
