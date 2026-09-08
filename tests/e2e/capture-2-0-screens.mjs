/**
 * Screenshot evidence for v1.2 sub-phase 2.0 (design language + ⌘K shell).
 * Run: node capture-2-0-screens.mjs  (BASE_URL defaults to http://localhost)
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

// Wait for dashboard stats to settle
await page.getByRole("heading", { name: "Operations Control" }).waitFor();
await page.waitForTimeout(2500);

// --- (a) Dashboard light ---
await page.getByRole("button", { name: "Toggle theme" }).click();
await page.getByRole("menuitem", { name: "Light" }).click();
await page.waitForTimeout(600);
await page.screenshot({ path: "/tmp/karyo-2-0-light.png", fullPage: false });

// --- (b) Dashboard dark ---
await page.getByRole("button", { name: "Toggle theme" }).click();
await page.getByRole("menuitem", { name: "Dark" }).click();
await page.waitForTimeout(600);
await page.screenshot({ path: "/tmp/karyo-2-0-dark.png", fullPage: false });

// --- (c) Open ⌘K palette (back in light for contrast with dark shot) ---
await page.keyboard.press("ControlOrMeta+k");
await page.getByRole("dialog").waitFor();
await page.waitForTimeout(400);
await page.screenshot({ path: "/tmp/karyo-2-0-cmdk.png", fullPage: false });

await browser.close();
console.log("Saved /tmp/karyo-2-0-{light,dark,cmdk}.png");
