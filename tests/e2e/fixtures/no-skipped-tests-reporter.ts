import type {
  FullResult,
  Reporter,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";

class NoSkippedTestsReporter implements Reporter {
  private readonly skipped = new Set<string>();

  onTestEnd(test: TestCase, result: TestResult): void {
    if (result.status === "skipped") {
      this.skipped.add(test.titlePath().join(" > "));
    }
  }

  async onEnd(): Promise<{ status?: FullResult["status"] } | void> {
    if (this.skipped.size === 0) return;
    console.error(
      `Deterministic Playwright suite skipped ${this.skipped.size} test(s):\n${[...this.skipped]
        .sort()
        .map((title) => `  - ${title}`)
        .join("\n")}`,
    );
    return { status: "failed" };
  }
}

export default NoSkippedTestsReporter;
