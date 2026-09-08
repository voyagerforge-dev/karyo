import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { cleanupE2EUsers } from "./db-cleanup.ts";
import { defaultBaseUrl, requiredCredential } from "./test-data.ts";

const TRACKED_ENV = [
  "BASE_URL",
  "KARYO_E2E_PROVISION",
  "KEYCLOAK_ADMIN_CLIENT_SECRET",
  "KARYO_E2E_ADMIN_USER",
  "KARYO_E2E_ADMIN_PASSWORD",
  "KARYO_E2E_MANAGER_USER",
  "KARYO_E2E_MANAGER_PASSWORD",
  "KARYO_E2E_OPERATOR_USER",
  "KARYO_E2E_OPERATOR_PASSWORD",
  "KARYO_E2E_VIEWER_USER",
  "KARYO_E2E_VIEWER_PASSWORD",
] as const;

const originalEnv = Object.fromEntries(
  TRACKED_ENV.map((name) => [name, process.env[name]]),
);
const originalFetch = globalThis.fetch;

afterEach(() => {
  for (const name of TRACKED_ENV) {
    const value = originalEnv[name];
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
  globalThis.fetch = originalFetch;
});

function clearTrackedEnv(): void {
  for (const name of TRACKED_ENV) delete process.env[name];
}

type FetchCall = {
  url: string;
  method: string;
  body: string;
  authorization: string | null;
  redirect: RequestRedirect | undefined;
};

function installFetch(handler: (call: FetchCall) => { status: number; body?: unknown }): FetchCall[] {
  const calls: FetchCall[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    const call: FetchCall = {
      url: String(input),
      method: (init?.method ?? "GET").toUpperCase(),
      body:
        typeof init?.body === "string"
          ? init.body
          : init?.body instanceof URLSearchParams
            ? init.body.toString()
            : "",
      authorization: headers.get("Authorization"),
      redirect: init?.redirect,
    };
    calls.push(call);
    const result = handler(call);
    return {
      ok: result.status >= 200 && result.status < 300,
      status: result.status,
      json: async () => result.body,
    } as Response;
  }) as typeof fetch;
  return calls;
}

describe("e2e cleanup origin and karyo-admin credentials", { concurrency: false }, () => {
  describe("defaultBaseUrl", () => {
    it("matches Playwright and run-e2e.sh when BASE_URL is unset", () => {
      clearTrackedEnv();
      assert.equal(defaultBaseUrl(), "http://localhost");
      assert.equal(defaultBaseUrl(undefined), "http://localhost");
      assert.equal(defaultBaseUrl(null), "http://localhost");
    });

    it("prefers an explicit origin, then BASE_URL, and never defaults to :8088", () => {
      clearTrackedEnv();
      process.env.BASE_URL = "http://localhost:8088";
      assert.equal(defaultBaseUrl(), "http://localhost:8088");
      assert.equal(defaultBaseUrl("http://example.test:90"), "http://example.test:90");
      delete process.env.BASE_URL;
      assert.equal(defaultBaseUrl(), "http://localhost");
      assert.doesNotMatch(defaultBaseUrl(), /:8088/);
    });
  });

  describe("requiredCredential", () => {
    it("fails immediately when a cleanup or login credential is unset", () => {
      clearTrackedEnv();
      assert.throws(
        () => requiredCredential("KEYCLOAK_ADMIN_CLIENT_SECRET"),
        /KEYCLOAK_ADMIN_CLIENT_SECRET is not set.*docs\/guides\/implementer-guide\.md#run-browser-acceptance/s,
      );
      assert.throws(
        () =>
          requiredCredential(
            "KEYCLOAK_ADMIN_CLIENT_SECRET",
            "KEYCLOAK_ADMIN_CLIENT_SECRET is not set. E2E user cleanup authenticates as the permanent karyo-admin service account and cannot fall back to a retired bootstrap administrator (see docs/guides/implementer-guide.md#run-browser-acceptance).",
          ),
        /cannot fall back to a retired bootstrap administrator/,
      );
    });
  });

  it("returns supplied credentials unchanged", () => {
    clearTrackedEnv();
    process.env.KEYCLOAK_ADMIN_CLIENT_SECRET = "provided-unit-test-secret";
    assert.equal(requiredCredential("KEYCLOAK_ADMIN_CLIENT_SECRET"), "provided-unit-test-secret");
  });

  describe("cleanupE2EUsers", () => {
    it("does not fetch when KEYCLOAK_ADMIN_CLIENT_SECRET is unset", async () => {
      clearTrackedEnv();
      process.env.KARYO_E2E_PROVISION = "true";
      const calls = installFetch(() => {
        throw new Error("cleanup must not fetch without karyo-admin credentials");
      });
      await assert.rejects(
        cleanupE2EUsers(),
        /KEYCLOAK_ADMIN_CLIENT_SECRET is not set.*cannot fall back to a retired bootstrap administrator.*docs\/guides\/implementer-guide\.md#run-browser-acceptance/s,
      );
      assert.deepEqual(calls, []);
    });

    it("token-grants karyo-admin with client credentials on the Playwright default origin", async () => {
      clearTrackedEnv();
      process.env.KARYO_E2E_PROVISION = "true";
      process.env.KEYCLOAK_ADMIN_CLIENT_SECRET = "unit-test-karyo-admin-secret";
      const leftoverId = "e2e-user-leftover-1";
      const calls = installFetch((call) => {
        if (call.url.endsWith("/protocol/openid-connect/token")) {
          return { status: 200, body: { access_token: "karyo-admin-token" } };
        }
        if (call.url.includes("/users?q=karyo_e2e%3Atrue")) {
          return { status: 200, body: [{ id: leftoverId }] };
        }
        if (call.url.endsWith(`/users/${leftoverId}`) && call.method === "GET") {
          return {
            status: 200,
            body: { username: "leftover", attributes: { karyo_e2e: ["true"] } },
          };
        }
        if (call.method === "DELETE" && call.url.endsWith(`/users/${leftoverId}`)) {
          return { status: 204 };
        }
        if (call.url.includes("username=e2e-managed-user")) {
          return { status: 200, body: [] };
        }
        return { status: 500, body: { error: call.url } };
      });

      await cleanupE2EUsers();

      const tokenCall = calls[0];
      assert.ok(tokenCall, "expected a token request");
      assert.equal(tokenCall.method, "POST");
      assert.equal(tokenCall.redirect, "manual");
      assert.equal(
        tokenCall.url,
        "http://localhost/auth/realms/karyo/protocol/openid-connect/token",
      );
      assert.doesNotMatch(tokenCall.url, /:8088/);
      assert.doesNotMatch(tokenCall.url, /\/realms\/master\//);

      const form = new URLSearchParams(tokenCall.body);
      assert.equal(form.get("client_id"), "karyo-admin");
      assert.equal(form.get("grant_type"), "client_credentials");
      assert.equal(form.get("client_secret"), "unit-test-karyo-admin-secret");
      assert.equal(form.get("username"), null);
      assert.equal(form.get("password"), null);
      assert.notEqual(form.get("client_id"), "admin-cli");
      assert.notEqual(form.get("grant_type"), "password");
      assert.ok(!tokenCall.body.includes("karyo_local_admin_pw"));
      assert.ok(!tokenCall.body.includes("admin-cli"));

      assert.equal(
        calls.some(
          (call) =>
            call.method === "DELETE" &&
            call.url === `http://localhost/auth/admin/realms/karyo/users/${leftoverId}` &&
            call.authorization === "Bearer karyo-admin-token",
        ),
        true,
        "expected leftover e2e users to be deleted with the karyo-admin bearer token",
      );
    });

    it("uses BASE_URL when the operator points Playwright at the smoke-stack origin", async () => {
      clearTrackedEnv();
      process.env.BASE_URL = "http://localhost:8088";
      process.env.KARYO_E2E_PROVISION = "true";
      process.env.KEYCLOAK_ADMIN_CLIENT_SECRET = "unit-test-karyo-admin-secret";
      const calls = installFetch((call) => {
        if (call.url.endsWith("/protocol/openid-connect/token")) {
          return { status: 401, body: { error: "unauthorized_client" } };
        }
        return { status: 500 };
      });

      await assert.rejects(cleanupE2EUsers(), /KC karyo-admin token 401/);
      assert.equal(
        calls[0]?.url,
        "http://localhost:8088/auth/realms/karyo/protocol/openid-connect/token",
      );
      assert.equal(calls.length, 1);
    });
  });
});
