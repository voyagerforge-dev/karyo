/**
 * Floor PWA receive E2E — inbound-completion sprint (Task 8).
 *
 * Seeds a RELEASED ASN + a dock-bound goods receipt as manager (client_id=1/ACME)
 * so the operator (also client_id=1/ACME) sees the same tenant's work pool, then
 * drives the receive screen through the floor PWA. Uses two separate browser
 * contexts, same as floor-pick.spec.ts / floor-move-count.spec.ts:
 *
 *   mgrCtx → Keycloak manager login → bearer token capture → API seeding
 *   opCtx  → Keycloak operator login → /m PWA → full receive flow
 *
 * Receive lifecycle note: ReceiveExecution (frontend/mobile/src/screens/
 * receive-execution.tsx) has NO location picker -- the operator always receives
 * onto the receipt's own dock (see its "no dock" honest-degradation banner), so
 * the seed phase MUST set dockLocationId/dockLocationName or the receive screen
 * refuses to render the line-entry flow.
 *
 * Work-pool race note (same as floor-pick.spec.ts): GET NEXT TASK dispatches the
 * whole eligible pool ordered by priority, not just this test's freshly-seeded
 * receipt -- a stale leftover PICK/MOVE/COUNT task from another spec can win the
 * race. If that happens, navigate directly to this test's own RECEIVE ref (the
 * receiveLine endpoint has no ownership check beyond order-write, and the screen
 * only GETs the receipt by id -- no claim required to view it).
 */
import { TEST_DATA } from '../fixtures/test-data'
import { test, expect } from '../fixtures/auth'
import { keycloakLogin } from '../fixtures/auth'
import { captureAuthHeader } from '../fixtures/api'
import { mobileLogin } from '../fixtures/mobile-auth'
import { seedAsn, seedGoodsReceipt, seedCountLocation, get, rawPost } from '../fixtures/scenario-helpers'

test.describe('Floor PWA — receive', () => {
  test('operator gets next, receives an ASN line via the receive screen', async ({ baseURL, browser }) => {
    test.setTimeout(120_000)
    const base = baseURL || "http://localhost"

    // ── 1. Seed as manager (client_id=1, ACME) ──────────────────────────────
    //
    //   • a dock location (any real location -- dockLocationId/Name are
    //     unvalidated denormalized scalars on GoodsReceipt, same convention as
    //     the per-line locationId/locationName)
    //   • a RELEASED ASN with one expected line (amount 40)
    //   • a goods receipt bound to that ASN, with the dock set -- CREATED,
    //     unclaimed, undocked receipts never surface in the floor work pool

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    // e2e- prefixed (not just 'rcvflr') so every seeded name starts with the
    // db-cleanup sweep's `e2e-%`/`E2E-%` LIKE patterns (asn_number, receipt_number,
    // storage_locations.name, item_data.number) -- otherwise a run that dies before
    // the finally block below strands live rows the next suite run never sees.
    const dock = await seedCountLocation(mgrPage, h, { prefix: 'e2e-rcvflr' })
    const asn = await seedAsn(mgrPage, h, { amount: 40, prefix: 'e2e-rcvflr' })
    const line = asn.lines[0]
    const receipt = await seedGoodsReceipt(mgrPage, h, {
      asnIds: [asn.id],
      dockLocationId: dock.locationId,
      dockLocationName: dock.locationName,
      prefix: 'e2e-rcvflr',
    })

    try {
      // ── 2. Drive /m as operator in a clean browser context ────────────────

      const opCtx = await browser.newContext()
      const op = await opCtx.newPage()
      try {
        await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)

        // ── 3. Claim the receive task from the work pool ──────────────────────

        await op.getByText('GET NEXT TASK').click()
        await op.waitForURL(/\/(pick|move|count|receive)\//, { timeout: 15_000 })

        const myRef = encodeURIComponent(`RECEIVE:${receipt.id}`)
        if (!op.url().includes(myRef)) {
          await op.goto(`${base}/m/receive/${myRef}`)
        }

        // ── 4. Phase: pick-line — select the (only) expected line ─────────────

        const lineButton = op.getByRole('button').filter({ hasText: line.itemDataNumber }).first()
        await lineButton.waitFor({ timeout: 15_000 })
        await lineButton.click()

        // ── 5. Phase: qty → confirm (qty pre-filled to the line's remaining=40) ─

        await op.getByText('REVIEW', { exact: true }).click()
        await op.getByText('CONFIRM RECEIVE', { exact: true }).click()

        // ── 6. Assert success ──────────────────────────────────────────────────

        await expect(op.getByText('Receiving complete')).toBeVisible({ timeout: 15_000 })
        await op.getByText('BACK TO INBOX').click()
      } finally {
        await opCtx.close()
      }

      // ── 7. Assert via API: the GR line exists with the right amount, visible
      //      to the desktop workbench too ─────────────────────────────────────

      const receiptAfter = await get<{
        lines: Array<{ itemDataId: number; amount: number; asnLineId: number | null }>
      }>(mgrPage, `/api/v1/goods-receipts/${receipt.id}`, h)
      const receivedLine = receiptAfter.lines.find((l) => l.asnLineId === line.id)
      expect(receivedLine, 'received line for the seeded ASN line').toBeTruthy()
      expect(Number(receivedLine!.amount)).toBe(40)
    } finally {
      // Best-effort: close out the receipt so no live STARTED (claimed-by-operator)
      // receipt survives this spec and strands the shared work pool for the next
      // suite run. Release first (the /m dispatch claims on GET NEXT TASK) -- as
      // manager (asManager=true) a release always succeeds regardless of who holds
      // the claim; finish itself has no claim check, so it's called unconditionally.
      // rawPost never throws on a non-2xx, so this can't mask an earlier assertion
      // failure or itself fail the spec if the receipt never got claimed/received.
      await rawPost(mgrPage, `/api/v1/goods-receipts/${receipt.id}/release`, {}, h)
      await rawPost(mgrPage, `/api/v1/goods-receipts/${receipt.id}/finish`, {}, h)
      await mgrCtx.close()
    }
  })
})
