// One-off evidence capture for v1.2 sub-phase 2.2 (Receiving).
// Drives the real UI (Keycloak login with supplied admin credentials) and screenshots:
//   /tmp/karyo-2-2-asn-detail.png   — ASN detail drawer (released, progress)
//   /tmp/karyo-2-2-workbench.png    — receiving workbench, received lines incl. a QA HOLD chip
//   /tmp/karyo-2-2-shortage.png     — amber shortage callout after force-finishing an ASN
import { chromium } from '@playwright/test';

const BASE = process.env.BASE_URL || "http://localhost";
const suffix = Date.now();

async function login(page) {
  const username = process.env.KARYO_E2E_ADMIN_USER;
  const password = process.env.KARYO_E2E_ADMIN_PASSWORD;
  if (!username || !password) {
    throw new Error(
      "KARYO_E2E_ADMIN_USER and KARYO_E2E_ADMIN_PASSWORD must be set. Karyo's production realm seeds no human users.",
    );
  }
  await page.goto(BASE);
  await page.waitForSelector('#username', { timeout: 30_000 });
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('#kc-login');
  await page.waitForURL((u) => !u.href.includes('/realms/'), { timeout: 30_000 });
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await login(page);

// keycloak-js keeps the token in memory only; capture it from a live API request
// the SPA fires during navigation (same trick as fixtures/api.ts captureAuthHeader).
const reqPromise = page.waitForRequest(
  (r) => r.url().includes('/api/v1/') && !!r.headers()['authorization'],
);
await page.goto(`${BASE}/locations`);
const auth = (await reqPromise).headers()['authorization'];
const headers = { Authorization: auth };
const post = async (p, d) => {
  const r = await page.request.post(`${BASE}${p}`, { headers, data: d });
  if (!r.ok()) throw new Error(`POST ${p}: ${r.status()} ${await r.text()}`);
  return r.json();
};
const get = async (p) => {
  const r = await page.request.get(`${BASE}${p}`, { headers });
  if (!r.ok()) throw new Error(`GET ${p}: ${r.status()} ${await r.text()}`);
  return r.json();
};

// --- Seed a product + receiving location + ASN (2 lines) via API ---
const itemUnits = await get('/api/v1/item-units');
const lt = await post('/api/v1/location-types', { name: `e2e-cap-shelf-${suffix}`, height: 200, width: 100, depth: 120, liftingCapacity: 500 });
const zone = await post('/api/v1/zones', { name: `e2e-cap-zone-${suffix}`, description: 'cap' });
const area = await post('/api/v1/areas', { name: `e2e-cap-area-${suffix}`, usages: [] });
const locName = `e2e-cap-loc-${suffix}`;
const loc = await post('/api/v1/locations', { name: locName, scanCode: locName, locationTypeId: lt.id, areaId: area.id, zoneId: zone.id });
const sku = `E2E-CAP-${suffix}`;
const product = await post('/api/v1/products', { number: sku, name: `e2e-cap-product-${suffix}`, description: 'cap', weight: 0.5, itemUnitId: itemUnits[0].id });
const asn = await post('/api/v1/asns', {
  asnNumber: `e2e-cap-${suffix}`,
  carrierName: 'Evidence Carrier',
  lines: [
    { itemDataId: product.id, expectedAmount: 100 },
    { itemDataId: product.id, expectedAmount: 50 },
  ],
});
await post(`/api/v1/asns/${asn.id}/release`, {});

// Open a receipt bound to the ASN, receive line 1 fully + line 2 partial w/ QA hold
const gr = await post('/api/v1/goods-receipts', { asnId: asn.id, carrierName: 'Evidence Carrier' });
const full = await get(`/api/v1/asns/${asn.id}`);
const l1 = full.lines.find((l) => l.lineNumber === 1);
const l2 = full.lines.find((l) => l.lineNumber === 2);
await post(`/api/v1/goods-receipts/${gr.id}/lines`, { asnLineId: l1.id, amount: 100, locationId: loc.id, locationName: locName, allowOverReceipt: false });
await post(`/api/v1/goods-receipts/${gr.id}/lines`, { asnLineId: l2.id, amount: 30, locationId: loc.id, locationName: locName, lockType: 103, allowOverReceipt: false });

// 1) ASN detail drawer
await page.goto(`${BASE}/asns`);
await page.getByPlaceholder('Search by ASN #, external # or carrier...').fill(asn.asnNumber);
const row = page.getByRole('row').filter({ hasText: asn.asnNumber });
await row.first().waitFor({ state: 'visible', timeout: 15_000 });
await row.first().click();
await page.getByTestId('asn-detail-drawer').waitFor({ state: 'visible', timeout: 15_000 });
await page.waitForTimeout(600);
await page.screenshot({ path: '/tmp/karyo-2-2-asn-detail.png', fullPage: false });
console.log('captured asn-detail');

// 2) Workbench with received lines incl. a QA HOLD chip
await page.goto(`${BASE}/receiving/${gr.id}`);
await page.getByTestId('receiving-workbench').waitFor({ state: 'visible', timeout: 15_000 });
await page.getByTestId('received-lines-table').getByText('QA HOLD').first().waitFor({ state: 'visible', timeout: 15_000 });
await page.waitForTimeout(600);
await page.screenshot({ path: '/tmp/karyo-2-2-workbench.png', fullPage: false });
console.log('captured workbench');

// 3) Shortage callout — force-finish the ASN (line 2 short 20) from the detail drawer
await page.goto(`${BASE}/asns`);
await page.getByPlaceholder('Search by ASN #, external # or carrier...').fill(asn.asnNumber);
const row2 = page.getByRole('row').filter({ hasText: asn.asnNumber });
await row2.first().waitFor({ state: 'visible', timeout: 15_000 });
await row2.first().click();
await page.getByTestId('asn-detail-drawer').waitFor({ state: 'visible', timeout: 15_000 });
try {
  await page.getByTestId('asn-finish-button').click({ timeout: 5_000 });
  await page.getByTestId('asn-finish-confirm').click({ timeout: 5_000 });
  await page.getByTestId('asn-shortage-callout').waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: '/tmp/karyo-2-2-shortage.png', fullPage: false });
  console.log('captured shortage');
} catch (e) {
  console.log('shortage capture skipped:', e.message);
}

await browser.close();
