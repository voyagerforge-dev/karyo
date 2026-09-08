import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { cleanupE2EArtifacts } from "../fixtures/db-cleanup";
import { get, rawGet, seedAreas, seedProduct, seedStockOnUL } from "../fixtures/scenario-helpers";

// Destructive artifact-sweep proof. Invoke only with an explicitly verified disposable
// KARYO_PG_CONTAINER and its KARYO_CONTAINER_CLI; never point it at customer storage.
test.use({ credentials: "manager" });

for (const prefix of ["e2e-cleanup", "E2E-CLEANUP"]) {
  test(`cleanup follows ${prefix} unit loads without deleting ordinary stock`, async ({ authenticatedPage: page }) => {
    expect(process.env.KARYO_PG_CONTAINER?.trim(), "a verified disposable database target is required").toBeTruthy();
    const headers = { Authorization: await captureAuthHeader(page) };
    const product = await seedProduct(page, headers, "cleanup-shared-product");
    const ownedAreas = await seedAreas(page, headers, prefix);
    const controlAreas = await seedAreas(page, headers, "cleanup-control");
    const owned = await seedStockOnUL(page, headers, { product, areas: ownedAreas, amount: 5 });
    const control = await seedStockOnUL(page, headers, { product, areas: controlAreas, amount: 7 });

    // A normal SKU can be moved to an E2E location. Stock must be removed before its
    // selected unit load, rather than rolling the entire sweep back on the inventory FK.
    cleanupE2EArtifacts();

    for (const path of [
      `/api/v1/stock-units/${owned.stockUnitId}`,
      `/api/v1/unit-loads/${owned.unitLoadId}`,
      `/api/v1/locations/${ownedAreas.storeLocId}`,
    ]) expect((await rawGet(page, path, headers)).status(), path).toBe(404);
    const remaining = await get<{ amount: number }>(page, `/api/v1/stock-units/${control.stockUnitId}`, headers);
    expect(Number(remaining.amount)).toBe(7);
    expect((await rawGet(page, `/api/v1/unit-loads/${control.unitLoadId}`, headers)).status()).toBe(200);
    expect((await rawGet(page, `/api/v1/locations/${controlAreas.storeLocId}`, headers)).status()).toBe(200);
    expect((await rawGet(page, `/api/v1/products/${product.id}`, headers)).status()).toBe(200);
  });
}
