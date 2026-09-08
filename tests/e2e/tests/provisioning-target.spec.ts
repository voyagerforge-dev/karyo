/**
 * Guards on which stack the suite may provision E2E identities against.
 *
 * These run without a browser. They cover the direct `npx playwright test` path specifically,
 * because that path bypasses scripts/run-e2e.sh entirely -- an attacker-shaped BASE_URL there
 * must still produce no request and no read of KEYCLOAK_ADMIN_CLIENT_SECRET.
 */
import { test, expect } from "@playwright/test";
import {
  ProvisioningTargetError,
  assertPermittedProvisioningTarget,
  resolveE2ETarget,
  resolvePermittedProvisioningTarget,
} from "../fixtures/provisioning-target";
import { cleanupE2EUsers, provisionE2EUsers } from "../fixtures/db-cleanup";
import { prepareE2EEnvironment } from "../global-setup";

const SENTINEL_SECRET = "sentinel-admin-client-secret-must-never-be-read";

/** Hostile and non-canonical shapes that must never reach the network or the secret. */
const REJECTED_TARGETS = [
  "http://localhost.attacker.test",
  "http://127.0.0.1.attacker.test",
  "http://attacker.test",
  "https://localhost@attacker.test",
  "http://user:pass@localhost:8088",
  "http://2130706433",
  "http://0177.0.0.1",
  "http://127.0.0.01",
  "http://localhost:8088/redirect",
  "http://localhost:8088?to=attacker.test",
  "http://localhost:8088#attacker.test",
  "http://LOCALHOST:8088",
  "http://localhost:80",
  "ftp://localhost",
  "//localhost:8088",
  "localhost:8088",
];

type EnvironmentOverrides = Record<string, string | undefined>;

/**
 * Swaps `process.env` for a recording proxy so a test can prove which variables were read.
 * Returns the recorder plus an explicit restore, rather than wrapping a callback, because the
 * bodies under test are async: a `finally` around the call would restore the real environment
 * the moment the body reached its first `await`.
 */
function recordEnvironment(overrides: EnvironmentOverrides): {
  reads: string[];
  restore: () => void;
} {
  const realEnv = process.env;
  const reads: string[] = [];
  const backing: EnvironmentOverrides = { ...realEnv, ...overrides };
  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) delete backing[key];
  }
  const proxy = new Proxy(backing, {
    get(target, property, receiver) {
      if (typeof property === "string") reads.push(property);
      return Reflect.get(target, property, receiver);
    },
  });
  Object.defineProperty(process, "env", { value: proxy, configurable: true, writable: true });
  return {
    reads,
    restore: () =>
      Object.defineProperty(process, "env", {
        value: realEnv,
        configurable: true,
        writable: true,
      }),
  };
}

function withEnvironment(overrides: EnvironmentOverrides, body: () => void): void {
  const { restore } = recordEnvironment(overrides);
  try {
    body();
  } finally {
    restore();
  }
}

async function attemptProvisioning(
  baseUrl: string,
  run: () => Promise<void>,
  overrides: EnvironmentOverrides = {},
): Promise<{ reads: string[]; requests: string[]; error: unknown }> {
  const realFetch = globalThis.fetch;
  const requests: string[] = [];
  globalThis.fetch = ((input: unknown) => {
    requests.push(String(input));
    return Promise.reject(new Error("network call escaped the provisioning-target guard"));
  }) as typeof fetch;

  const { reads, restore } = recordEnvironment({
    BASE_URL: baseUrl,
    KARYO_E2E_PROVISION: "true",
    KEYCLOAK_ADMIN_CLIENT_SECRET: SENTINEL_SECRET,
    KARYO_E2E_ADMIN_PASSWORD: "e2e-admin-password-for-guard-check",
    KARYO_E2E_MANAGER_PASSWORD: "e2e-manager-password-for-guard-check",
    KARYO_E2E_OPERATOR_PASSWORD: "e2e-operator-password-for-guard-check",
    KARYO_E2E_ALLOW_REMOTE_PROVISION: undefined,
    KARYO_E2E_EXPECTED_ORIGIN: undefined,
    ...overrides,
  });

  let error: unknown;
  try {
    await run();
  } catch (thrown) {
    error = thrown;
  } finally {
    restore();
    globalThis.fetch = realFetch;
  }
  return { reads, requests, error };
}

test("loopback targets are permitted and returned canonically", () => {
  expect(assertPermittedProvisioningTarget("http://localhost")).toBe("http://localhost");
  expect(assertPermittedProvisioningTarget("http://localhost:8088")).toBe("http://localhost:8088");
  expect(assertPermittedProvisioningTarget("http://localhost:8088/")).toBe("http://localhost:8088");
  expect(assertPermittedProvisioningTarget("http://127.0.0.1:8088")).toBe("http://127.0.0.1:8088");
  expect(assertPermittedProvisioningTarget("http://[::1]:8088")).toBe("http://[::1]:8088");
  expect(resolvePermittedProvisioningTarget("http://localhost:8088").locality).toBe("loopback");
});

test("hostile and non-canonical targets are refused", () => {
  for (const target of REJECTED_TARGETS) {
    expect(
      () => assertPermittedProvisioningTarget(target),
      `expected ${target} to be refused`,
    ).toThrow(ProvisioningTargetError);
  }
});

test("global setup cleans only a loopback target's local database", async () => {
  const cleanupCalls: string[] = [];
  const remoteEnvironment = recordEnvironment({
    BASE_URL: "https://wms.example.test",
    KARYO_E2E_PROVISION: undefined,
  });
  try {
    await prepareE2EEnvironment(() => cleanupCalls.push("remote"));
  } finally {
    remoteEnvironment.restore();
  }

  const loopbackEnvironment = recordEnvironment({
    BASE_URL: "http://localhost:8088",
    KARYO_E2E_PROVISION: undefined,
  });
  try {
    await prepareE2EEnvironment(() => cleanupCalls.push("loopback"));
  } finally {
    loopbackEnvironment.restore();
  }

  expect(resolveE2ETarget("https://wms.example.test").locality).toBe("remote");
  expect(cleanupCalls).toEqual(["loopback"]);
});

test("a remote target needs both the opt-in and an exact declared origin", () => {
  const remote = "https://wms.example.test";

  withEnvironment(
    { KARYO_E2E_ALLOW_REMOTE_PROVISION: undefined, KARYO_E2E_EXPECTED_ORIGIN: undefined },
    () => {
      expect(() => assertPermittedProvisioningTarget(remote)).toThrow(
        /KARYO_E2E_ALLOW_REMOTE_PROVISION=true/,
      );
    },
  );

  withEnvironment(
    { KARYO_E2E_ALLOW_REMOTE_PROVISION: "true", KARYO_E2E_EXPECTED_ORIGIN: undefined },
    () => {
      expect(() => assertPermittedProvisioningTarget(remote)).toThrow(/KARYO_E2E_EXPECTED_ORIGIN/);
    },
  );
});

test("an opted-in remote run is bound to its exact HTTPS origin", () => {
  withEnvironment(
    {
      KARYO_E2E_ALLOW_REMOTE_PROVISION: "true",
      KARYO_E2E_EXPECTED_ORIGIN: "https://wms.example.test",
    },
    () => {
      expect(resolvePermittedProvisioningTarget("https://wms.example.test")).toEqual({
        origin: "https://wms.example.test",
        locality: "remote",
      });
      expect(() => assertPermittedProvisioningTarget("https://wms.attacker.test")).toThrow(
        /does not match KARYO_E2E_EXPECTED_ORIGIN/,
      );
    },
  );

  withEnvironment(
    {
      KARYO_E2E_ALLOW_REMOTE_PROVISION: "true",
      KARYO_E2E_EXPECTED_ORIGIN: "http://wms.example.test",
    },
    () => {
      expect(() => assertPermittedProvisioningTarget("http://wms.example.test")).toThrow(/HTTPS/);
    },
  );
});

for (const [label, run] of [
  ["provisionE2EUsers", provisionE2EUsers],
  ["cleanupE2EUsers", cleanupE2EUsers],
] as const) {
  test(`${label} refuses opted-in remote HTTP before reading credentials`, async () => {
    const target = "http://wms.example.test";
    const { reads, requests, error } = await attemptProvisioning(target, run, {
      KARYO_E2E_ALLOW_REMOTE_PROVISION: "true",
      KARYO_E2E_EXPECTED_ORIGIN: target,
    });

    expect(requests).toEqual([]);
    expect(reads.includes("KEYCLOAK_ADMIN_CLIENT_SECRET")).toBe(false);
    expect(error).toBeInstanceOf(ProvisioningTargetError);
    expect(String(error)).toContain("HTTPS");
  });

  test(`${label} makes no request and reads no secret for a hostile BASE_URL`, async () => {
    for (const target of ["http://localhost.attacker.test", "http://127.0.0.1.attacker.test"]) {
      const { reads, requests, error } = await attemptProvisioning(target, run);

      expect(requests, `${label} issued a request to ${target}`).toEqual([]);
      expect(
        reads.includes("KEYCLOAK_ADMIN_CLIENT_SECRET"),
        `${label} read the admin-client secret for ${target}`,
      ).toBe(false);
      expect(error, `${label} accepted ${target}`).toBeInstanceOf(ProvisioningTargetError);
    }
  });

  test(`${label} makes no request and reads no secret without the exact opt-in`, async () => {
    const realFetch = globalThis.fetch;
    const requests: string[] = [];
    globalThis.fetch = ((input: unknown) => {
      requests.push(String(input));
      return Promise.reject(new Error("network call escaped provisioning opt-in"));
    }) as typeof fetch;
    const { reads, restore } = recordEnvironment({
      BASE_URL: "http://localhost:8088",
      KARYO_E2E_PROVISION: undefined,
      KEYCLOAK_ADMIN_CLIENT_SECRET: SENTINEL_SECRET,
    });
    try {
      await expect(run()).rejects.toThrow(/KARYO_E2E_PROVISION=true is required/);
      expect(requests).toEqual([]);
      expect(reads.includes("KEYCLOAK_ADMIN_CLIENT_SECRET")).toBe(false);
    } finally {
      restore();
      globalThis.fetch = realFetch;
    }
  });
}

test("provisioning creates and role-maps every ephemeral browser identity", async () => {
  const realFetch = globalThis.fetch;
  const requests: Array<{ url: string; method: string; body: string }> = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    const body = typeof init?.body === "string" ? init.body : init?.body?.toString() ?? "";
    requests.push({ url, method, body });
    if (url.endsWith("/protocol/openid-connect/token")) {
      return new Response(JSON.stringify({ access_token: "admin-token" }), { status: 200 });
    }
    if (url.includes("/roles/") && method === "GET") {
      const name = decodeURIComponent(url.substring(url.lastIndexOf("/") + 1));
      return new Response(JSON.stringify({ id: `role-${name}`, name }), { status: 200 });
    }
    if (url.endsWith("/users") && method === "POST") return new Response(null, { status: 201 });
    if (url.includes("/users?username=") && method === "GET") {
      const username = new URL(url).searchParams.get("username");
      return new Response(JSON.stringify([{ id: `id-${username}` }]), { status: 200 });
    }
    if (url.endsWith("/role-mappings/realm") && method === "POST") {
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 500 });
  }) as typeof fetch;
  const { restore } = recordEnvironment({
    BASE_URL: "http://localhost:8088",
    KARYO_E2E_PROVISION: "true",
    KEYCLOAK_ADMIN_CLIENT_SECRET: "test-admin-client-secret",
    KARYO_E2E_ADMIN_PASSWORD: "test-admin-password",
    KARYO_E2E_MANAGER_PASSWORD: "test-manager-password",
    KARYO_E2E_OPERATOR_PASSWORD: "test-operator-password",
    KARYO_E2E_VIEWER_PASSWORD: "test-viewer-password",
  });
  try {
    await provisionE2EUsers();
    const creates = requests.filter(
      ({ url, method }) => method === "POST" && url.endsWith("/auth/admin/realms/karyo/users"),
    );
    expect(creates).toHaveLength(4);
    expect(creates.map(({ body }) => JSON.parse(body).username)).toEqual([
      "e2e-suite-admin",
      "e2e-suite-manager",
      "e2e-suite-operator",
      "e2e-suite-viewer",
    ]);
    expect(
      requests.filter(({ url, method }) => method === "POST" && url.endsWith("/role-mappings/realm")),
    ).toHaveLength(4);
    expect(
      creates.every(({ body }) => JSON.parse(body).attributes.karyo_e2e[0] === "true"),
    ).toBe(true);
    expect([
      process.env.KARYO_E2E_ADMIN_USER,
      process.env.KARYO_E2E_MANAGER_USER,
      process.env.KARYO_E2E_OPERATOR_USER,
      process.env.KARYO_E2E_VIEWER_USER,
    ]).toEqual([
      "e2e-suite-admin",
      "e2e-suite-manager",
      "e2e-suite-operator",
      "e2e-suite-viewer",
    ]);
  } finally {
    restore();
    globalThis.fetch = realFetch;
  }
});

/**
 * A redirect off the token endpoint is where a hijacked or misconfigured origin would harvest
 * the karyo-admin client secret. `keycloakFetch` sends `redirect: "manual"` and refuses any 3xx,
 * so the secret reaches exactly the declared origin and is never replayed to the Location target.
 */
test("a token endpoint redirect is refused without forwarding the secret", async () => {
  const realFetch = globalThis.fetch;
  const requests: Array<{ url: string; body: string; redirect: RequestRedirect | undefined }> = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({
      url: String(input),
      body: typeof init?.body === "string" ? init.body : init?.body?.toString() ?? "",
      redirect: init?.redirect,
    });
    return new Response(null, {
      status: 307,
      headers: { Location: "https://attacker.test/protocol/openid-connect/token" },
    });
  }) as typeof fetch;
  const { restore } = recordEnvironment({
    BASE_URL: "http://localhost:8088",
    KARYO_E2E_PROVISION: "true",
    KEYCLOAK_ADMIN_CLIENT_SECRET: SENTINEL_SECRET,
  });
  try {
    await expect(cleanupE2EUsers()).rejects.toThrow(/Keycloak request refused redirect 307/);
    expect(requests).toHaveLength(1);
    expect(requests[0].redirect).toBe("manual");
    expect(requests[0].url).toBe(
      "http://localhost:8088/auth/realms/karyo/protocol/openid-connect/token",
    );
    expect(new URLSearchParams(requests[0].body).get("client_secret")).toBe(SENTINEL_SECRET);
  } finally {
    restore();
    globalThis.fetch = realFetch;
  }
});
