/**
 * Warehouse workflow E2E test -- API-driven receive -> putaway -> pick -> ship.
 *
 * Successor of the retired guided-tour coverage: drives the same operational
 * sequence the tour performed, but through REST calls only (the UI is used
 * solely for login/token acquisition via the auth + api fixtures).
 *
 * Sequence (mirrors the old walkthrough step actions):
 *  1. create a unit load at the receiving location
 *  2. receive stock on it (state INCOMING=100)
 *  3. confirm receipt (change-state -> ON_STOCK=300)
 *  4. putaway: transfer the unit load to a storage location
 *  5. pick (change-state -> PICKED=600)
 *  6. stage: transfer to the shipping location
 *  7. ship (change-state -> SHIPPED=680)
 *
 * Stock unit state + location are asserted via GET after every step.
 *
 * Test data uses unique e2e-/E2E- prefixed names so the spec never collides
 * with the demo sample data and gets swept by the global-setup DB cleanup.
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";

// StockState codes (match com.karyo.inventory StockState / legacy myWMS)
const INCOMING = 100;
const ON_STOCK = 300;
const PICKED = 600;
const SHIPPED = 680;

test.describe("Warehouse workflow (API-driven)", () => {
  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // same fix as putaway.spec.ts / picking.spec.ts / packing.spec.ts.
  test.use({ credentials: "manager" });

  test("receive -> putaway -> pick -> ship via REST", async ({
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
    type StockUnit = Entity & {
      state: number;
      stateName: string;
      locationId: number;
      locationName: string;
      amount: number;
      unitLoadId: number;
    };
    type UnitLoad = Entity & {
      storageLocationId: number;
      storageLocationName: string;
    };
    type Location = Entity & { name: string; allocation: number };

    // --- Setup: minimal layout + master data (unique names, no demo coupling) ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);
    const itemUnitId = itemUnits[0].id;

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-wf-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });
    const zone = await post<Entity>(page, "/api/v1/zones", {
      name: `e2e-wf-zone-${suffix}`,
      description: "Workflow E2E zone",
    });
    const area = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-wf-area-${suffix}`,
      usages: [],
    });

    const createLocation = (name: string) =>
      post<Location>(page, "/api/v1/locations", {
        name,
        scanCode: name,
        locationTypeId: locationType.id,
        areaId: area.id,
        zoneId: zone.id,
      });
    const receiving = await createLocation(`e2e-wf-rcv-${suffix}`);
    const storage = await createLocation(`e2e-wf-str-${suffix}`);
    const shipping = await createLocation(`e2e-wf-shp-${suffix}`);

    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-wf-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-WF-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-wf-product-${suffix}`,
      description: "Workflow E2E product",
      weight: 0.5,
      itemUnitId,
    });

    // --- 1. Create a unit load at the receiving location ---

    const unitLoad = await post<UnitLoad>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PAL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: receiving.id,
      storageLocationName: receiving.name,
    });
    expect(unitLoad.storageLocationId).toBe(receiving.id);

    // --- 2. Receive stock (INCOMING) ---

    const created = await post<StockUnit>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 100,
      unitLoadId: unitLoad.id,
      state: INCOMING,
    });
    const stockId = created.id;

    let stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.state).toBe(INCOMING);
    expect(stock.amount).toBe(100);
    expect(stock.locationId).toBe(receiving.id);
    expect(stock.locationName).toBe(receiving.name);

    // --- 3. Confirm receipt (-> ON_STOCK) ---

    await post(page, `/api/v1/stock-units/${stockId}/change-state`, {
      state: ON_STOCK,
    });
    stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.state).toBe(ON_STOCK);
    expect(stock.stateName).toBe("ON_STOCK");

    // --- 4. Putaway: transfer unit load to storage ---

    const afterPutaway = await post<UnitLoad>(
      page,
      `/api/v1/unit-loads/${unitLoad.id}/transfer`,
      {
        destinationLocationId: storage.id,
        destinationLocationName: storage.name,
      },
    );
    expect(afterPutaway.storageLocationId).toBe(storage.id);
    expect(afterPutaway.storageLocationName).toBe(storage.name);

    stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.state).toBe(ON_STOCK);
    expect(stock.locationId).toBe(storage.id);
    expect(stock.locationName).toBe(storage.name);

    // Layout allocation follows the unit load (100% per UL, synchronous)
    const storageAfterPutaway = await get<Location>(
      page,
      `/api/v1/locations/${storage.id}`,
    );
    expect(Number(storageAfterPutaway.allocation)).toBe(100);

    // --- 5. Pick (-> PICKED) ---

    await post(page, `/api/v1/stock-units/${stockId}/change-state`, {
      state: PICKED,
    });
    stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.state).toBe(PICKED);
    expect(stock.stateName).toBe("PICKED");

    // --- 6. Stage for shipping: transfer unit load to the shipping dock ---

    const afterStaging = await post<UnitLoad>(
      page,
      `/api/v1/unit-loads/${unitLoad.id}/transfer`,
      {
        destinationLocationId: shipping.id,
        destinationLocationName: shipping.name,
      },
    );
    expect(afterStaging.storageLocationId).toBe(shipping.id);

    stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.locationId).toBe(shipping.id);
    expect(stock.locationName).toBe(shipping.name);

    // Allocation moved from storage to the shipping dock
    const storageAfterStaging = await get<Location>(
      page,
      `/api/v1/locations/${storage.id}`,
    );
    const shippingAfterStaging = await get<Location>(
      page,
      `/api/v1/locations/${shipping.id}`,
    );
    expect(Number(storageAfterStaging.allocation)).toBe(0);
    expect(Number(shippingAfterStaging.allocation)).toBe(100);

    // --- 7. Ship (-> SHIPPED) ---

    await post(page, `/api/v1/stock-units/${stockId}/change-state`, {
      state: SHIPPED,
    });
    stock = await get<StockUnit>(page, `/api/v1/stock-units/${stockId}`);
    expect(stock.state).toBe(SHIPPED);
    expect(stock.stateName).toBe("SHIPPED");
    expect(stock.locationId).toBe(shipping.id);
  });
});
