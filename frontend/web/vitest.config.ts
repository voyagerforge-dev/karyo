import { readFileSync } from 'node:fs';
import { availableParallelism } from 'node:os';
import { defineConfig, type Plugin } from 'vitest/config';
import path from 'path';

type VitestContract = {
  runner: string;
  include: string[];
  exclude: string[];
  setup_files: string[];
};

const runnerContracts = JSON.parse(
  readFileSync(new URL('../../config/test-runner-contracts.json', import.meta.url), 'utf8'),
) as { contracts: VitestContract[] };
const testContract = runnerContracts.contracts.find(({ runner }) => runner === 'vitest-web');
if (!testContract) throw new Error('missing vitest-web runner contract');

/**
 * Upper bound on concurrent test workers.
 *
 * The suite is jsdom-heavy and every worker competes for the same cores, so oversubscribing the
 * machine starves workers of CPU and tests fail on their timeout rather than on their assertions.
 * The failure is suite-wide, not specific to any slow test: it reaches tests that only ever call
 * fireEvent. Vitest's own default is `cpus - 1`, which already oversubscribes a build machine
 * running anything else, and an explicit `--maxWorkers` on the command line can ask for far more.
 *
 * Measured on a 16-core machine: wall time is flat from 4 to 16 workers (53s to 65s), so a low
 * ceiling costs nothing measurable while leaving cores for whatever else shares the machine - the
 * CI runner shares its box with three sibling runners and the forge.
 */
const WORKER_CEILING = 8;

/**
 * Clamps the worker count to {@link WORKER_CEILING}, bounded by the machine's own core count.
 *
 * This is a plugin hook rather than a plain `test.maxWorkers` entry because Vitest merges command
 * line options over the config file: `--maxWorkers=32` silently replaces a configured value.
 * `configureVitest` runs after that merge and sees the resolved project config, so the ceiling
 * holds no matter what the invoker asks for. A request for *fewer* workers is still honoured -
 * this only ever lowers the count.
 */
function capTestWorkers(ceiling: number): Plugin {
  const cap = Math.max(1, Math.min(availableParallelism(), ceiling));
  return {
    name: 'karyo-cap-test-workers',
    configureVitest({ project }) {
      const requested = project.config.maxWorkers;
      // `undefined` means Vitest would fall back to `cpus - 1`, which is above the ceiling.
      if (typeof requested !== 'number' || requested > cap) {
        project.config.maxWorkers = cap;
      }
    },
  };
}

export default defineConfig({
  plugins: [capTestWorkers(WORKER_CEILING)],
  test: {
    globals: true,
    environment: 'jsdom',
    include: testContract.include,
    exclude: testContract.exclude,
    setupFiles: testContract.setup_files,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
