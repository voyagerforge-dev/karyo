import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig, devices } from "@playwright/test";
import { DEFAULT_BASE_URL } from "./fixtures/provisioning-target";

type PlaywrightContract = {
  runner: string;
  test_dir: string;
  test_match: string;
  forbid_only: boolean;
  forbid_skips: boolean;
  skip_reporter: string;
};

const runnerContracts = JSON.parse(
  readFileSync(resolve(__dirname, "../../config/test-runner-contracts.json"), "utf8"),
) as { contracts: PlaywrightContract[] };
const testContract = runnerContracts.contracts.find(({ runner }) => runner === "playwright");
if (!testContract) throw new Error("missing Playwright runner contract");
if (!testContract.forbid_only) throw new Error("Playwright focused-test rejection is required");
if (!testContract.forbid_skips) throw new Error("Playwright skipped-test rejection is required");

export default defineConfig({
  testDir: `./${testContract.test_dir}`,
  testMatch: testContract.test_match,
  fullyParallel: false,
  forbidOnly: testContract.forbid_only,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [["html"], [testContract.skip_reporter]],
  timeout: 60_000,
  globalSetup: "./global-setup.ts",
  globalTeardown: "./global-teardown.ts",

  use: {
    baseURL: process.env.BASE_URL || DEFAULT_BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
