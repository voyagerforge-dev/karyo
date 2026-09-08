import { test, expect, keycloakLogin } from "../fixtures/auth";
import { fetchLicenseEntitlements } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";

test.describe("Wave-based bulk fulfillment unlicensed lock", () => {
  test("waves screen shows the locked panel when advanced-fulfillment is not entitled", async ({
    page,
    baseURL,
  }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);
    expect(await fetchLicenseEntitlements(page)).toEqual([]);

    await page.goto(base + "/waves");
    await expect(page.getByTestId("waves-page")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("waves-locked")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("Wave fulfillment is a paid add-on")).toBeVisible();
    await expect(page.getByTestId("waves-board")).not.toBeAttached();
  });
});
