import { readFileSync } from 'node:fs'
import { defineConfig } from 'vitest/config'
import path from 'path'

type VitestContract = {
  runner: string
  include: string[]
  exclude: string[]
  setup_files: string[]
}

const runnerContracts = JSON.parse(
  readFileSync(new URL('../../config/test-runner-contracts.json', import.meta.url), 'utf8'),
) as { contracts: VitestContract[] }
const testContract = runnerContracts.contracts.find(({ runner }) => runner === 'vitest-mobile')
if (!testContract) throw new Error('missing vitest-mobile runner contract')

export default defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
    include: testContract.include,
    exclude: testContract.exclude,
    setupFiles: testContract.setup_files,
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
})
