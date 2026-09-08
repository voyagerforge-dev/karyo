/**
 * Floor PWA pick E2E — capstone for v1.4 Slice 1.
 *
 * Seeds a single-line delivery order via REST as manager (client_id=1/ACME) so
 * the operator (also client_id=1/ACME) can see the same tenant's work pool.
 * Uses two separate browser contexts:
 *
 *   mgrCtx  → Keycloak manager login → bearer token capture → API seeding
 *   opCtx   → Keycloak operator login → /m PWA → full pick flow
 *
 * Tenant note: admin (client_id=0/SYS) and operator (client_id=1/ACME) are in
 * different tenants. The default authenticatedPage fixture logs in as admin, so
 * we open a fresh mgrCtx and login as manager instead. browser.newContext()
 * does not inherit baseURL from the Playwright config — pass it explicitly.
 *
 * Flow: /m login → GET NEXT TASK → scan location → scan item → CONFIRM → "Pick complete" → BACK TO INBOX
 */
import { TEST_DATA } from '../fixtures/test-data'
import { test, expect } from '../fixtures/auth'
import { keycloakLogin } from '../fixtures/auth'
import { captureAuthHeader } from '../fixtures/api'
import { mobileLogin } from '../fixtures/mobile-auth'
import {
  seedAreas,
  seedProduct,
  seedStockOnUL,
  createOrder,
  releaseOrder,
  releaseToPicking,
} from '../fixtures/scenario-helpers'

test.describe('Floor PWA — pick', () => {
  test('operator gets next, scans, confirms a pick', async ({ baseURL, browser }) => {
    test.setTimeout(120_000)
    const base = baseURL || "http://localhost"

    // ── 1. Seed as manager (client_id=1, ACME) ──────────────────────────────
    //
    // browser.newContext() doesn't inherit playwright.config baseURL, so pass it
    // explicitly. This ensures page.goto('/locations') and page.request.post(...)
    // with relative paths resolve correctly.

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const headers = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, headers, 'flr')
    const product = await seedProduct(mgrPage, headers, 'flr')
    await seedStockOnUL(mgrPage, headers, { product, areas, amount: 10, prefix: 'flr' })

    const order = await createOrder(mgrPage, headers, {
      lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount: 10 }],
      prefix: 'flr',
    })
    await releaseOrder(mgrPage, headers, order.id)
    const pickOrder = await releaseToPicking(mgrPage, headers, order.id)
    await mgrCtx.close()

    // ── 2. Drive /m as operator in a clean browser context ──────────────────

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)

      // ── 3. Claim the pick from the work pool ───────────────────────────────

      await op.getByText('GET NEXT TASK').click()
      // Inbox mutation navigates to the claimed task's route on success --
      // /m/pick/<ref> for PICK, but /m/move/<ref> for anything else
      // (PUTAWAY/REPLENISH), /m/count/<ref> for COUNT, and /m/receive/<ref>
      // for RECEIVE (see `routeFor` in `frontend/mobile/src/lib/work-api.ts`).
      // The dispatch strategy orders the whole eligible pool (any leftover
      // claimable PUTAWAY/COUNT/RECEIVE work from earlier specs included),
      // not just this test's freshly-seeded pick, so a stale task of a
      // DIFFERENT type can be claimed first -- wait for any of the four
      // routes, not just /pick/.
      await op.waitForURL(/\/(pick|move|count|receive)\//, { timeout: 15_000 })

      // Safety: if a stale task (wrong ACME pick, or a wrong task TYPE
      // entirely -- e.g. a leftover PUTAWAY) was claimed instead of ours,
      // navigate directly to the seeded pick order. The confirm endpoint is
      // @RolesAllowed("fulfillment-write") only — no ownership check, and
      // PickExecution only GETs the pick order by id (no claim required),
      // so driving straight to our own ref is safe.
      const myRef = encodeURIComponent(`PICK:${pickOrder.id}`)
      if (!op.url().includes(myRef)) {
        await op.goto(`${base}/m/pick/${myRef}`)
      }

      // ── 4. Phase: location — ScanField only renders once stock unit loads ──

      const locInput = op.getByLabel('Scan location')
      await locInput.waitFor({ timeout: 15_000 })
      await locInput.fill(areas.storeLocName)
      await locInput.press('Enter')

      // ── 5. Phase: item ─────────────────────────────────────────────────────

      const itemInput = op.getByLabel('Scan item')
      await itemInput.waitFor({ timeout: 10_000 })
      await itemInput.fill(product.number)
      await itemInput.press('Enter')

      // ── 6. Phase: qty → confirm (qty pre-filled to plannedAmount = 10) ─────

      await op.getByText('CONFIRM', { exact: true }).click()

      // ── 7. Assert done ─────────────────────────────────────────────────────

      await expect(op.getByText('Pick complete')).toBeVisible({ timeout: 15_000 })
      await op.getByText('BACK TO INBOX').click()
    } finally {
      await opCtx.close()
    }
  })
})
