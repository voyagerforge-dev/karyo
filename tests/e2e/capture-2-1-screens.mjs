/**
 * Screenshot evidence for v1.2 sub-phase 2.1 (Orders module).
 * Run: node capture-2-1-screens.mjs  (BASE_URL defaults to http://localhost)
 *
 * Seeds its own E2E-SHOT-* product/stock + three orders via the API
 * (one PROCESSABLE, one RELEASED-with-shortage, one CREATED), then captures:
 *   /tmp/karyo-2-1-orders-list.png   -- orders list
 *   /tmp/karyo-2-1-order-detail.png  -- detail drawer with reserved lines + actions
 *   /tmp/karyo-2-1-shortage.png      -- shortage callout
 */
import { chromium } from "@playwright/test";

const BASE_URL = process.env.BASE_URL || "http://localhost";
const username = process.env.KARYO_E2E_ADMIN_USER;
const password = process.env.KARYO_E2E_ADMIN_PASSWORD;
if (!username || !password) {
  throw new Error(
    "KARYO_E2E_ADMIN_USER and KARYO_E2E_ADMIN_PASSWORD must be set. Karyo's production realm seeds no human users.",
  );
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

await page.goto(BASE_URL);
await page.waitForSelector("#username", { timeout: 30_000 });
await page.fill("#username", username);
await page.fill("#password", password);
await page.click("#kc-login");
await page.waitForURL((url) => !url.href.includes("/realms/"), {
  timeout: 30_000,
});

// --- Capture a bearer token from the SPA's first API call ---
const reqPromise = page.waitForRequest(
  (r) => r.url().includes("/api/v1/") && !!r.headers()["authorization"],
);
await page.goto(`${BASE_URL}/locations`);
const authorization = (await reqPromise).headers()["authorization"];
const headers = { Authorization: authorization };

async function post(path, data) {
  const res = await page.request.post(`${BASE_URL}${path}`, { headers, data });
  if (!res.ok()) throw new Error(`POST ${path}: ${res.status()} ${await res.text()}`);
  return res.json();
}
async function get(path) {
  const res = await page.request.get(`${BASE_URL}${path}`, { headers });
  if (!res.ok()) throw new Error(`GET ${path}: ${res.status()}`);
  return res.json();
}

// --- Seed product + 100 ON_STOCK, plus three orders in different states ---
const suffix = Date.now();
const itemUnits = await get("/api/v1/item-units");
const locationType = await post("/api/v1/location-types", {
  name: `e2e-shot-shelf-${suffix}`, height: 200, width: 100, depth: 120, liftingCapacity: 500,
});
const zone = await post("/api/v1/zones", { name: `e2e-shot-zone-${suffix}`, description: "Screenshot zone" });
const area = await post("/api/v1/areas", { name: `e2e-shot-area-${suffix}`, usages: [] });
const location = await post("/api/v1/locations", {
  name: `e2e-shot-loc-${suffix}`, scanCode: `e2e-shot-loc-${suffix}`,
  locationTypeId: locationType.id, areaId: area.id, zoneId: zone.id,
});
const ult = await post("/api/v1/unit-load-types", {
  name: `e2e-shot-pallet-${suffix}`, height: 150, width: 120, depth: 100, weight: 25, liftingCapacity: 1500,
});
const ul = await post("/api/v1/unit-loads", {
  labelId: `E2E-SHOT-PAL-${suffix}`, unitLoadTypeId: ult.id,
  storageLocationId: location.id, storageLocationName: `e2e-shot-loc-${suffix}`,
});
const sku = `E2E-SHOT-${suffix}`;
const product = await post("/api/v1/products", {
  number: sku, name: "Wireless Headset", description: "Screenshot product", weight: 0.3, itemUnitId: itemUnits[0].id,
});
const stock = await post("/api/v1/stock-units", {
  itemDataId: product.id, itemDataNumber: sku, amount: 100, unitLoadId: ul.id, state: 100,
});
await post(`/api/v1/stock-units/${stock.id}/change-state`, { state: 300 });

const orderA = await post("/api/v1/delivery-orders", {
  orderNumber: `e2e-shot-${suffix}-a`, customerName: "Northwind Traders", prio: 50,
  deliveryDate: "2026-06-20",
  lines: [{ itemDataId: product.id, amount: 40 }],
});
await post(`/api/v1/delivery-orders/${orderA.id}/release`, {});
const orderB = await post("/api/v1/delivery-orders", {
  orderNumber: `e2e-shot-${suffix}-b`, customerName: "Contoso Ltd", prio: 20,
  lines: [{ itemDataId: product.id, amount: 500 }],
});
await post(`/api/v1/delivery-orders/${orderB.id}/release`, {});
await post("/api/v1/delivery-orders", {
  orderNumber: `e2e-shot-${suffix}-c`, customerName: "Acme Corp", prio: 80,
  deliveryDate: "2026-06-25",
  lines: [{ itemDataId: product.id, amount: 10 }],
});

// --- (a) Orders list ---
await page.goto(`${BASE_URL}/orders`);
await page.getByRole("heading", { name: "Orders" }).waitFor();
await page.waitForTimeout(1500);
await page.screenshot({ path: "/tmp/karyo-2-1-orders-list.png" });

// --- (b) Detail drawer: PROCESSABLE order with reserved lines + actions ---
await page
  .getByPlaceholder("Search by order # or customer...")
  .fill(`e2e-shot-${suffix}-a`);
await page.getByRole("row").filter({ hasText: `e2e-shot-${suffix}-a` }).click();
await page.getByTestId("order-detail-drawer").waitFor();
await page.waitForTimeout(1000);
await page.screenshot({ path: "/tmp/karyo-2-1-order-detail.png" });
await page.keyboard.press("Escape");

// --- (c) Shortage callout: RELEASED order with PENDING line ---
await page
  .getByPlaceholder("Search by order # or customer...")
  .fill(`e2e-shot-${suffix}-b`);
await page.getByRole("row").filter({ hasText: `e2e-shot-${suffix}-b` }).click();
await page.getByTestId("shortage-callout").waitFor();
await page.waitForTimeout(800);
await page.screenshot({ path: "/tmp/karyo-2-1-shortage.png" });

await browser.close();
console.log("Saved /tmp/karyo-2-1-{orders-list,order-detail,shortage}.png");
