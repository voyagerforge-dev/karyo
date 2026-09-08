import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig } from "@playwright/test";
import baseConfig from "./playwright.config";

type OperationalContract = {
  project_root: string;
  test_dir: string;
  test_match: string;
};

const runnerContracts = JSON.parse(
  readFileSync(resolve(__dirname, "../../config/test-runner-contracts.json"), "utf8"),
) as { playwright_operational: OperationalContract };
const operational = runnerContracts.playwright_operational;

export default defineConfig(baseConfig, {
  testDir: `./${operational.test_dir}`,
  testMatch: operational.test_match,
});
