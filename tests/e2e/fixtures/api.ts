import type { Page } from "@playwright/test";

/**
 * Capture the SPA's bearer token by observing an authenticated API request
 * fired during a page navigation. keycloak-js keeps the token in memory only,
 * so it cannot be read from cookies or storage. The returned header value can
 * be used with page.request.* for API-level test setup.
 */
export async function captureAuthHeader(
  page: Page,
  path = "/locations",
): Promise<string> {
  const reqPromise = page.waitForRequest(
    (r) => r.url().includes("/api/v1/") && !!r.headers()["authorization"],
  );
  await page.goto(path);
  const req = await reqPromise;
  return req.headers()["authorization"]!;
}

export async function fetchLicenseEntitlements(page: Page): Promise<string[]> {
  const authorization = await captureAuthHeader(page);
  const response = await page.request.get("/api/v1/license", {
    headers: { Authorization: authorization },
  });
  if (!response.ok()) {
    throw new Error(`GET /api/v1/license failed with ${response.status()}`);
  }
  const body: unknown = await response.json();
  const entitlements =
    typeof body === "object" && body !== null
      ? (body as { entitlements?: unknown }).entitlements
      : undefined;
  if (!Array.isArray(entitlements) || !entitlements.every((value) => typeof value === "string")) {
    throw new Error("GET /api/v1/license returned malformed entitlements");
  }
  return entitlements;
}
