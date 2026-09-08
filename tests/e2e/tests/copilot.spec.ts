import { test, expect, keycloakLogin } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";

test.describe("Copilot disabled state", () => {
  test("default provider reports disabled and hides the launcher", async ({ page, baseURL }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);

    const authorization = await captureAuthHeader(page);
    const response = await page.request.get("/api/v1/ai/config", {
      headers: { Authorization: authorization },
    });
    expect(response.ok()).toBe(true);
    expect(await response.json()).toEqual({ enabled: false, provider: "none" });

    await page.goto(base + "/");
    await expect(page.getByRole("button", { name: "Open copilot" })).not.toBeAttached();
  });
});
