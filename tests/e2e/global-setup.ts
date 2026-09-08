import {
  cleanupE2EArtifacts,
  cleanupE2EUsers,
  e2eProvisioningEnabled,
  provisionE2EUsers,
} from "./fixtures/db-cleanup";
import {
  DEFAULT_BASE_URL,
  resolveE2ETarget,
} from "./fixtures/provisioning-target";

/**
 * Global setup: sweep "e2e-"/"E2E-" entities from a loopback stack and leftover
 * Keycloak e2e users from previous runs. Local database artifact cleanup is non-fatal
 * (unique suffixes still isolate specs) and is skipped for remote targets. Keycloak
 * mutation happens only with the explicit provisioning opt-in; an opted-in cleanup
 * or provision failure aborts.
 */
export async function prepareE2EEnvironment(
  cleanupLocalArtifacts: () => void = cleanupE2EArtifacts,
): Promise<void> {
  const target = resolveE2ETarget(process.env.BASE_URL ?? DEFAULT_BASE_URL);
  if (target.locality === "loopback") {
    try {
      cleanupLocalArtifacts();
      console.log("[global-setup] Cleaned e2e-* DB artifacts from previous runs");
    } catch (err) {
      console.warn("[global-setup] Could not clean e2e DB artifacts (continuing):", err instanceof Error ? err.message : err);
    }
  } else {
    console.log(`[global-setup] Remote target ${target.origin}; leaving local database unchanged`);
  }
  if (!e2eProvisioningEnabled()) {
    console.log("[global-setup] KARYO_E2E_PROVISION is not true; leaving Keycloak unchanged");
    return;
  }
  await cleanupE2EUsers();
  await provisionE2EUsers();
}

export default async function globalSetup(): Promise<void> {
  await prepareE2EEnvironment();
}
