import { test, expect, keycloakLogin } from "../fixtures/auth";
import { TEST_DATA, uniqueName } from "../fixtures/test-data";

test.describe("Webhook delivery receiver", () => {
  test("send test event produces a DELIVERED delivery row", async ({ page, baseURL }) => {
    const targetUrl = process.env.E2E_WEBHOOK_URL;
    expect(targetUrl, "E2E_WEBHOOK_URL must be reachable from the karyo-app container").toBeTruthy();

    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);
    const name = uniqueName("wh-dlv");

    await page.goto(base + "/admin/integrations");
    await expect(page.getByTestId("admin-integrations-page")).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "+ New subscription" }).click();
    await page.getByPlaceholder("Acme ERP").fill(name);
    await page.getByPlaceholder("https://acme.internal/wms-hooks").fill(targetUrl!);
    const eventsInput = page.getByPlaceholder("*");
    await eventsInput.clear();
    await eventsInput.fill("*");
    await page.getByRole("button", { name: "Create" }).click();
    await expect(page.getByText(/won.t be shown again/)).toBeVisible({ timeout: 10_000 });
    await page.getByRole("button", { name: "Done" }).click();
    await page.getByText(name).click();
    await page.getByRole("button", { name: "Send test" }).click();

    await expect(async () => {
      await page.reload();
      await expect(page.getByTestId("admin-integrations-page")).toBeVisible();
      await page.getByText(name).click();
      await expect(page.getByText("DELIVERED").first()).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 20_000 });
  });
});
