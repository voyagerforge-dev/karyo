import { test, expect, keycloakLogin } from "../fixtures/auth";
import { TEST_DATA } from "../fixtures/test-data";

const provider = process.env.KARYO_AI_PROVIDER;

test.describe("Copilot live provider", () => {
  test("copilot launcher responds to a warehouse question", async ({ page, baseURL }) => {
    expect(["anthropic", "ollama"]).toContain(provider);
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);
    await page.goto(base + "/");

    const launcher = page.getByRole("button", { name: "Open copilot" });
    await expect(launcher).toBeVisible({ timeout: 20_000 });
    await launcher.click();
    await expect(page.getByText("Karyo Copilot")).toBeVisible({ timeout: 10_000 });

    const messageInput = page.getByLabel("Message input");
    await expect(messageInput).toBeVisible({ timeout: 5_000 });
    await messageInput.fill("What is my warehouse occupancy?");
    await page.getByLabel("Send message").click();
    await expect(page.getByText("Thinking…")).toBeVisible({ timeout: 10_000 });
    await expect(page.locator(".prose").first()).toBeVisible({ timeout: 60_000 });
  });
});
