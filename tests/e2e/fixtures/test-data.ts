import { DEFAULT_BASE_URL } from "./provisioning-target.ts";

/**
 * Reusable test data constants and helpers for E2E tests.
 * Generates unique names to avoid collisions across parallel test runs.
 */

/**
 * Generate a unique name with a prefix and timestamp suffix.
 * Example: uniqueName("product") -> "e2e-product-1710284567123"
 */
export function uniqueName(prefix: string): string {
  return `e2e-${prefix}-${Date.now()}`;
}

/**
 * Generate a unique SKU with a prefix.
 * Example: uniqueSku("MOUSE") -> "E2E-MOUSE-1710284567123"
 */
export function uniqueSku(prefix: string): string {
  return `E2E-${prefix}-${Date.now()}`;
}

/** Same default origin as Playwright and scripts/run-e2e.sh. */
export function defaultBaseUrl(explicit?: string | null): string {
  return explicit || process.env.BASE_URL || DEFAULT_BASE_URL;
}

/**
 * Read one provisioned E2E credential, or fail by name.
 *
 * The production realm seeds no human users. Global setup supplies usernames after provisioning
 * the ephemeral role-specific identities, while the runner supplies generated passwords. An
 * unset variable throws immediately instead of timing out at the Keycloak form.
 */
export const E2E_PROVISIONED_USERNAMES = {
  ADMIN: "e2e-suite-admin",
  MANAGER: "e2e-suite-manager",
  OPERATOR: "e2e-suite-operator",
  VIEWER: "e2e-suite-viewer",
} as const;

function e2eUsername(role: keyof typeof E2E_PROVISIONED_USERNAMES): string {
  const variable = `KARYO_E2E_${role}_USER`;
  const supplied = process.env[variable];
  if (supplied) return supplied;
  if (process.env.KARYO_E2E_PROVISION === "true") return E2E_PROVISIONED_USERNAMES[role];
  return requiredCredential(variable);
}

export function requiredCredential(variable: string, detail?: string): string {
  const value = process.env[variable];
  if (!value) {
    throw new Error(
      detail ??
        `${variable} is not set. Karyo's production realm seeds no human users, so enable ` +
          "ephemeral E2E provisioning or supply credentials (see docs/guides/implementer-guide.md#run-browser-acceptance).",
    );
  }
  return value;
}

/** Default test data constants */
export const TEST_DATA = {
  /** Admin user credentials */
  get admin() {
    return {
      username: e2eUsername("ADMIN"),
      password: requiredCredential("KARYO_E2E_ADMIN_PASSWORD"),
    };
  },

  /** Operator user credentials */
  get operator() {
    return {
      username: e2eUsername("OPERATOR"),
      password: requiredCredential("KARYO_E2E_OPERATOR_PASSWORD"),
    };
  },

  /** Manager user credentials */
  get manager() {
    return {
      username: e2eUsername("MANAGER"),
      password: requiredCredential("KARYO_E2E_MANAGER_PASSWORD"),
    };
  },

  /** Viewer user credentials (RBAC negative cases; no fulfillment roles) */
  get viewer() {
    return {
      username: e2eUsername("VIEWER"),
      password: requiredCredential("KARYO_E2E_VIEWER_PASSWORD"),
    };
  },

  /**
   * Expected sidebar nav items for admin role (subset check -- the live
   * sidebar has ~19 items across 5 permission-gated groups, see
   * `frontend/web/src/config/navigation.ts`; this is not exhaustive).
   * "Products" was retired 2026-07-15 -- the product catalog now lives at
   * "Items".
   */
  adminNavItems: ["Control", "Locations", "Items", "Inventory", "Users"],

  /** Expected sidebar nav items for operator role (no Users) */
  operatorNavItems: ["Control", "Locations", "Items", "Inventory"],

  /** Known page routes */
  routes: {
    dashboard: "/",
    locations: "/locations",
    items: "/items",
    inventory: "/inventory",
    users: "/users",
  },
} as const;
