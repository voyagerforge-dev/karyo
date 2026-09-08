/**
 * Screenshot the Control redesign (Operations Control + Inventory) on :8088.
 * Run from tests/e2e: node capture-redesign.mjs
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
const page = await browser.newPage({ viewport: { width: 1480, height: 1000 } });

await page.goto(BASE_URL);
await page.waitForSelector("#username", { timeout: 30_000 });
await page.fill("#username", username);
await page.fill("#password", password);
await page.click("#kc-login");
await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });

// Operations Control (default Control theme)
await page.getByRole("heading", { name: "Operations Control" }).waitFor();
await page.waitForTimeout(2000);
await page.screenshot({ path: "/tmp/karyo-redesign-control.png", fullPage: true });

// Inventory
await page.goto(`${BASE_URL}/inventory`);
await page.getByRole("heading", { name: "Inventory" }).waitFor();
await page.waitForTimeout(2000);
await page.screenshot({ path: "/tmp/karyo-redesign-inventory.png", fullPage: true });

await browser.close();
console.log("Saved /tmp/karyo-redesign-{control,inventory}.png");
