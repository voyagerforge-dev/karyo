import { test, expect, keycloakLogin } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { defaultBaseUrl, TEST_DATA } from "../fixtures/test-data";
import {
  createOrder, get, post, releaseOrder, seedAreas, seedProduct, uniq,
  type Entity,
} from "../fixtures/scenario-helpers";

// This explicitly invoked proof needs an augmented example image. Run the same proof with
// KARYO_EXPECT_INVENTORY_EXAMPLE=false against a stock image for the negative control.
function expectsExample(): boolean {
  const value = process.env.KARYO_EXPECT_INVENTORY_EXAMPLE ?? "true";
  if (value !== "true" && value !== "false") {
    throw new Error("KARYO_EXPECT_INVENTORY_EXAMPLE must be true or false");
  }
  return value === "true";
}

test("the live registry reflects build-time example installation", async ({ authenticatedPage: page }) => {
  const headers = { Authorization: await captureAuthHeader(page) };
  const entries = await get<Array<{ spiInterface: string; implementations: string[] }>>(
    page, "/api/v1/admin/extensions", headers,
  );
  const seam = entries.find((entry) => entry.spiInterface === "StockSelectionFilter");
  expect(seam, "StockSelectionFilter is a public API seam").toBeDefined();
  expect(seam!.implementations.includes("HeldLotStockFilter")).toBe(expectsExample());
  expect(seam!.implementations).not.toContain("HazmatStockFilter");
});

test("order reservation observes the example without bypassing locked-stock safety", async ({ page, baseURL }) => {
  await keycloakLogin(page, defaultBaseUrl(baseURL), TEST_DATA.manager.username, TEST_DATA.manager.password);
  const headers = { Authorization: await captureAuthHeader(page) };
  const areas = await seedAreas(page, headers, "extension");
  const product = await seedProduct(page, headers, "extension");
  const ids: number[] = [];
  // Held stock is oldest, so ordinary FIFO selects it. Each test owns its IDs and lots.
  for (const [lotPrefix, locked] of [["EXAMPLE-HOLD-", false], ["NORMAL-", true], ["NORMAL-", false]] as const) {
    const unitLoad = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1, labelId: uniq("extension-ul"), unitLoadTypeId: areas.unitLoadTypeId,
      storageLocationId: areas.storeLocId, storageLocationName: areas.storeLocName,
    }, headers);
    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id, itemDataNumber: product.number, amount: 10,
      unitLoadId: unitLoad.id, lotNumber: `${lotPrefix}${uniq()}`, state: 100,
    }, headers);
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, { state: 300 }, headers);
    if (locked) await post(page, `/api/v1/stock-units/${stock.id}/lock`, { lockType: 1 }, headers);
    ids.push(stock.id);
  }
  const order = await createOrder(page, headers, {
    prefix: "extension", lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 15 }],
  });
  const released = await releaseOrder(page, headers, order.id);
  const enabled = expectsExample();
  expect(Number(released.lines[0].shortage)).toBe(enabled ? 5 : 0);
  expect(Number(released.lines[0].reservedAmount)).toBe(enabled ? 10 : 15);
  const reserved: number[] = [];
  for (const id of ids) {
    const stock = await get<{ amount: number; reservedAmount: number; availableAmount: number }>(
      page, `/api/v1/stock-units/${id}`, headers,
    );
    reserved.push(Number(stock.reservedAmount));
    expect(Number(stock.amount) - Number(stock.reservedAmount)).toBe(Number(stock.availableAmount));
    expect(Number(stock.availableAmount)).toBeGreaterThanOrEqual(0);
  }
  expect(reserved).toEqual(enabled ? [0, 0, 10] : [10, 0, 5]);
});
