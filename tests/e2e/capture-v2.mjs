/** Screenshot the v2 redesign screens on :8088. Run from tests/e2e. */
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

const desktop = [
  ["reports", "/insights/reports", "reports-page"],
  ["monitors", "/insights/monitors", "monitors-page"],
  ["clients", "/3pl/clients", "clients-page"],
  ["optimize", "/optimize", "optimize-page"],
  ["pack", "/pack", "packing-station-page"],
];
for (const [name, route, testid] of desktop) {
  await page.goto(`${BASE_URL}${route}`);
  await page.getByTestId(testid).waitFor({ timeout: 20_000 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `/tmp/karyo-v2-${name}.png`, fullPage: true });
}

// Console density = Command (click the toggle on the dashboard)
await page.goto(`${BASE_URL}/`);
await page.getByRole("heading", { name: "Operations Control" }).waitFor();
await page.getByTestId("dashboard-home").getByRole("button", { name: "Command", exact: true }).click();
await page.waitForTimeout(800);
await page.screenshot({ path: "/tmp/karyo-v2-density-command.png", fullPage: false });

// RF screens at a handheld viewport
await page.setViewportSize({ width: 440, height: 920 });
for (const [name, route, testid] of [
  ["rf-pick", "/rf/pick", "rf-pick-page"],
  ["rf-putaway", "/rf/putaway", "rf-putaway-page"],
]) {
  await page.goto(`${BASE_URL}${route}`);
  await page.getByTestId(testid).waitFor({ timeout: 20_000 });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `/tmp/karyo-v2-${name}.png`, fullPage: false });
}

await browser.close();
console.log("Saved /tmp/karyo-v2-*.png");
