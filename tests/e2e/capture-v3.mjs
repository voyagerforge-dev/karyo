/** Screenshot the v3 redesign screens on :8088. Run from tests/e2e. */
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
const page = await browser.newPage({ viewport: { width: 1480, height: 1000 } });

await page.goto(BASE_URL);
await page.waitForSelector("#username", { timeout: 30_000 });
await page.fill("#username", username);
await page.fill("#password", password);
await page.click("#kc-login");
await page.waitForURL((u) => !u.href.includes("/realms/"), { timeout: 30_000 });
await page.getByRole("heading", { name: "Operations Control" }).waitFor();

const screens = [
  ["orders", "/orders", "orders-page"],
  ["inventory", "/inventory", "inventory-page"],
  ["items", "/items", "items-page"],
  ["locations", "/locations", "locations-page"],
  ["admin-strategies", "/admin/strategies", "admin-strategies-page"],
];
for (const [name, route, testid] of screens) {
  await page.goto(`${BASE_URL}${route}`);
  await page.getByTestId(testid).waitFor({ timeout: 20_000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `/tmp/karyo-v3-${name}.png`, fullPage: false });
}

// Workspace panel (Console) + a non-lime accent to prove the wiring
await page.goto(`${BASE_URL}/`);
await page.getByRole("heading", { name: "Operations Control" }).waitFor();
await page.getByTestId("workspace-gear").click();
await page.waitForTimeout(700);
await page.screenshot({ path: "/tmp/karyo-v3-workspace.png", fullPage: false });
// Try switching accent to Violet, then capture the dashboard recolored
const violet = page.getByRole("button", { name: "Violet", exact: true });
if (await violet.count()) {
  await violet.first().click();
  await page.waitForTimeout(500);
  await page.keyboard.press("Escape");
  await page.waitForTimeout(500);
  await page.screenshot({ path: "/tmp/karyo-v3-accent-violet.png", fullPage: false });
}

await browser.close();
console.log("Saved /tmp/karyo-v3-*.png");
