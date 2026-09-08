import { test, expect, keycloakLogin } from "../fixtures/auth";
import { fetchLicenseEntitlements } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";

test.describe("Document templates unlicensed lock", () => {
  test("admin document templates shows the locked panel when documents is not entitled", async ({
    page,
    baseURL,
  }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.admin.username, TEST_DATA.admin.password);
    expect(await fetchLicenseEntitlements(page)).toEqual([]);

    await page.goto(base + "/admin/document-templates");
    await expect(page.getByTestId("admin-templates-page")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("templates-locked")).toBeVisible({ timeout: 15_000 });
  });
});
