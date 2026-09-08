/**
 * The single authority on which stack the E2E suite may provision identities against.
 *
 * This exists once because there are two entry paths -- `scripts/run-e2e.sh` and a direct
 * `npx playwright test` -- and a guard that lives in only one of them is not a guard. The shell
 * wrapper runs this module as a CLI; the provisioning fixtures call it in-process. Both resolve
 * the target through here BEFORE `KEYCLOAK_ADMIN_CLIENT_SECRET` is read, so an attacker-shaped
 * BASE_URL never reaches the point where the admin-client secret would be expanded or sent.
 */

/** Exact loopback authorities. Anything else is a remote target. */
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export const DEFAULT_BASE_URL = "http://localhost";

export class ProvisioningTargetError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProvisioningTargetError";
  }
}

/**
 * Parses one value into its browser-canonical origin, rejecting anything that is not exactly
 * that origin already.
 *
 * Requiring `input === url.origin` is what rejects the alternate encodings a bare allowlist
 * misses: `http://0177.0.0.1` and `http://2130706433` both parse to `http://127.0.0.1`, and
 * `http://localhost:80` to `http://localhost`, so comparing the parsed hostname alone would
 * accept forms the operator never wrote. Credentials, path, query and fragment are refused
 * outright rather than normalized away.
 */
function canonicalOrigin(input: string, label: string): string {
  let url: URL;
  try {
    url = new URL(input);
  } catch {
    throw new ProvisioningTargetError(`${label} must be an absolute HTTP(S) URL`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new ProvisioningTargetError(`${label} must use http or https`);
  }
  if (url.username || url.password) {
    throw new ProvisioningTargetError(`${label} must not carry userinfo`);
  }
  if (!url.hostname) {
    throw new ProvisioningTargetError(`${label} must name a host`);
  }
  if (url.search || url.hash || (url.pathname !== "" && url.pathname !== "/")) {
    throw new ProvisioningTargetError(
      `${label} must be a bare origin with no path, query or fragment`,
    );
  }
  const origin = url.origin;
  if (origin === "null") {
    throw new ProvisioningTargetError(`${label} has no usable origin`);
  }
  const supplied = input.endsWith("/") ? input.slice(0, -1) : input;
  if (supplied !== origin) {
    throw new ProvisioningTargetError(
      `${label} must be written in its canonical browser origin form (${origin})`,
    );
  }
  return origin;
}

/**
 * Resolves the canonical origin the suite is allowed to provision against, or throws.
 *
 * Loopback is permitted on its own. A remote target needs TLS, the explicit opt-in, and a
 * separately declared exact origin: the opt-in alone would let any later change to BASE_URL --
 * accidental or attacker-influenced -- redirect the admin-client secret somewhere new, because
 * a boolean cannot say *which* remote stack was authorised.
 */
export type E2ETarget = {
  origin: string;
  locality: "loopback" | "remote";
};

export function resolveE2ETarget(rawBaseUrl: string): E2ETarget {
  const origin = canonicalOrigin(rawBaseUrl, "BASE_URL");
  const locality = LOOPBACK_HOSTS.has(new URL(origin).hostname) ? "loopback" : "remote";
  return { origin, locality };
}

export function resolvePermittedProvisioningTarget(rawBaseUrl: string): E2ETarget {
  const target = resolveE2ETarget(rawBaseUrl);
  if (target.locality === "loopback") {
    return target;
  }
  if (new URL(target.origin).protocol !== "https:") {
    throw new ProvisioningTargetError(
      `Refusing to provision E2E identities against non-loopback target ${target.origin} without HTTPS`,
    );
  }
  if (process.env.KARYO_E2E_ALLOW_REMOTE_PROVISION !== "true") {
    throw new ProvisioningTargetError(
      `Refusing to provision E2E identities against non-loopback target ${target.origin} without ` +
        "KARYO_E2E_ALLOW_REMOTE_PROVISION=true",
    );
  }
  const declared = process.env.KARYO_E2E_EXPECTED_ORIGIN;
  if (!declared) {
    throw new ProvisioningTargetError(
      `Remote target ${target.origin} also requires KARYO_E2E_EXPECTED_ORIGIN naming the exact origin ` +
        "this run is authorised to reach",
    );
  }
  const expected = canonicalOrigin(declared, "KARYO_E2E_EXPECTED_ORIGIN");
  if (expected !== target.origin) {
    throw new ProvisioningTargetError(
      `BASE_URL origin ${target.origin} does not match KARYO_E2E_EXPECTED_ORIGIN ${expected}`,
    );
  }
  return target;
}

export function assertPermittedProvisioningTarget(rawBaseUrl: string): string {
  return resolvePermittedProvisioningTarget(rawBaseUrl).origin;
}

/** The validated origin every provisioning request must be addressed to. */
export function provisioningTarget(): string {
  return assertPermittedProvisioningTarget(process.env.BASE_URL ?? DEFAULT_BASE_URL);
}
