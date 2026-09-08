import { cleanupE2EUsers, e2eProvisioningEnabled } from "./fixtures/db-cleanup";

/**
 * Global teardown: remove the ephemeral Keycloak identities global setup provisioned.
 *
 * Without this the run's human accounts survive indefinitely -- enabled, role-bearing, and
 * holding a password that existed only in the run's shell environment. Global setup still sweeps
 * leftovers, but only as a safety net for a run that died before teardown.
 */
export default async function globalTeardown(): Promise<void> {
  if (!e2eProvisioningEnabled()) return;
  try {
    await cleanupE2EUsers();
  } catch (err) {
    console.error(
      "[global-teardown] Could not remove Keycloak e2e users -- remove them manually:",
      err instanceof Error ? err.message : err,
    );
    throw err;
  }
}
