/**
 * Command-line front end to the provisioning-target guard, used by scripts/run-e2e.sh.
 *
 * Kept separate from the module itself so the guard has no import-time side effect and stays
 * loadable both by Playwright (which transpiles to CommonJS) and by `node` running this file
 * directly. It adds no policy of its own -- the shell wrapper and the Playwright fixtures reach
 * the same single implementation.
 *
 * Prints the canonical origin on success; prints the refusal reason to stderr and exits 2
 * otherwise. It never reads or echoes a credential.
 */
import {
  DEFAULT_BASE_URL,
  resolvePermittedProvisioningTarget,
} from "./provisioning-target.ts";

try {
  const classify = process.argv[2] === "--classify";
  const target = resolvePermittedProvisioningTarget(
    process.argv[classify ? 3 : 2] ?? DEFAULT_BASE_URL,
  );
  process.stdout.write(classify ? `${target.origin}\t${target.locality}` : target.origin);
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(2);
}
