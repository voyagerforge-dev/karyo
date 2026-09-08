/**
 * Floor PWA move + count E2E — capstone for v1.4 Slice 2.
 *
 * Seeds as manager (client_id=1/ACME) so the operator (also client_id=1/ACME)
 * sees the work in the same tenant. Uses two separate browser contexts:
 *
 *   mgrCtx → Keycloak manager login → bearer token capture → API seeding
 *   opCtx  → Keycloak operator login → /m PWA → full move / count flow
 *
 * Move lifecycle note: TaskService.complete() requires STARTED state (not RELEASED).
 * The seed phase advances the transport order to RESERVED (assign only).  The move
 * screen calls start (RESERVED→STARTED) then complete when the operator taps COMPLETE,
 * so the E2E exercises the real post-claim path at RESERVED.
 *
 * Count lifecycle note: StartCountRequest creates the order in GENERATED (50) state.
 * SubmitCount works from GENERATED state directly — no explicit start/claim required.
 */
import { TEST_DATA } from '../fixtures/test-data'
import { test, expect } from '../fixtures/auth'
import { keycloakLogin } from '../fixtures/auth'
import { captureAuthHeader } from '../fixtures/api'
import { mobileLogin } from '../fixtures/mobile-auth'
import {
  seedAreas,
  seedProduct,
  seedCountLocation,
  seedStockAtLocation,
  startCount,
  post,
  uniq,
  INCOMING,
  ON_STOCK,
  type Entity,
} from '../fixtures/scenario-helpers'

type TransportOrderResp = {
  id: number
  unitLoadLabel: string
  destinationLocationName: string | null
  suggestedLocationName: string | null
  state: number
}

test.describe('Floor PWA — move + count', () => {

  // ── MOVE ──────────────────────────────────────────────────────────────────

  test('operator scans and completes a move task', async ({ baseURL, browser }) => {
    test.setTimeout(120_000)
    const base = baseURL || "http://localhost"

    // ── 1. Seed as manager ───────────────────────────────────────────────────
    //
    // Manager creates:
    //   • source area + location (via seedAreas)
    //   • a second storage location as destination
    //   • a unit-load at the source location
    //   • stock on the unit-load (ON_STOCK)
    //   • a manual MOVE transport order → advanced to RESERVED (assign) via API

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, h, 'mv')
    const s = uniq('mv')

    // Destination: a second location in the same STORAGE area.
    const destLocName = `${s}-dest`
    const destLoc = await post<Entity>(mgrPage, '/api/v1/locations', {
      name: destLocName,
      scanCode: destLocName,
      locationTypeId: areas.locationTypeId,
      areaId: areas.storeAreaId,
    }, h)

    // Source unit-load (label kept short to fit any field length constraints).
    const ulLabel = `${s.slice(-12).toUpperCase()}-MV`
    const ul = await post<Entity>(mgrPage, '/api/v1/unit-loads', {
      clientId: 1,
      labelId: ulLabel,
      unitLoadTypeId: areas.unitLoadTypeId,
      storageLocationId: areas.storeLocId,
      storageLocationName: areas.storeLocName,
    }, h)

    // Stock on unit-load (ON_STOCK so the move is eligible).
    const product = await seedProduct(mgrPage, h, 'mv')
    const stock = await post<Entity>(mgrPage, '/api/v1/stock-units', {
      itemDataId: product.id,
      itemDataNumber: product.number,
      amount: 10,
      unitLoadId: ul.id,
      state: INCOMING,
    }, h)
    await post(mgrPage, `/api/v1/stock-units/${stock.id}/change-state`, { state: ON_STOCK }, h)

    // Create manual MOVE transport order — arrives RELEASED (100).
    const transportOrder = await post<TransportOrderResp>(mgrPage, '/api/v1/transport-orders', {
      unitLoadId: ul.id,
      destinationLocationId: destLoc.id,
      destinationLocationName: destLocName,
    }, h)

    // Advance to RESERVED (assign): RELEASED→RESERVED.  The move screen performs
    // start (RESERVED→STARTED) itself before calling complete, so we stop here.
    await post<TransportOrderResp>(
      mgrPage,
      `/api/v1/transport-orders/${transportOrder.id}/assign`,
      { operatorId: 'operator' },
      h,
    )

    await mgrCtx.close()

    // ── 2. Drive /m as operator ───────────────────────────────────────────────

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)

      // Navigate directly to the move screen (task at RESERVED — screen will start then complete).
      const moveRef = encodeURIComponent(`MOVE:${transportOrder.id}`)
      await op.goto(`${base}/m/move/${moveRef}`)
      await op.waitForURL(/\/move\//, { timeout: 10_000 })

      // Phase: source — wait for the UL scan field, enter the unit-load label.
      const ulInput = op.getByLabel('Scan unit-load')
      await ulInput.waitFor({ timeout: 15_000 })
      await ulInput.fill(transportOrder.unitLoadLabel)
      await ulInput.press('Enter')

      // Phase: destination — scan the destination location name.
      const destInput = op.getByLabel('Scan location')
      await destInput.waitFor({ timeout: 10_000 })
      await destInput.fill(destLocName)
      await destInput.press('Enter')

      // COMPLETE button appears once destConfirmed=true (after DEST_OK dispatch).
      await op.getByText('COMPLETE', { exact: true }).click()

      // Assert success banner.
      await expect(op.getByText('Move complete')).toBeVisible({ timeout: 15_000 })
      await op.getByText('BACK TO INBOX').click()
    } finally {
      await opCtx.close()
    }
  })

  // ── COUNT ─────────────────────────────────────────────────────────────────

  test('operator scans and submits a count task', async ({ baseURL, browser }) => {
    test.setTimeout(120_000)
    const base = baseURL || "http://localhost"

    // ── 1. Seed as manager ───────────────────────────────────────────────────
    //
    // Manager creates:
    //   • a STORAGE location + product + stock (ON_STOCK)
    //   • a cycle-count session for that location (order arrives in GENERATED=50 state)

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const product = await seedProduct(mgrPage, h, 'cnt')
    const loc = await seedCountLocation(mgrPage, h, { prefix: 'cnt' })
    await seedStockAtLocation(mgrPage, h, {
      product,
      locationId: loc.locationId,
      locationName: loc.locationName,
      locationTypeId: loc.locationTypeId,
      amount: 10,
      prefix: 'cnt',
    })

    // Start count session → GENERATED (50) state; one order per location.
    const session = await startCount(mgrPage, h, [loc.locationId])
    const countOrder = session.orders[0]
    const countLine = countOrder.lines[0]

    await mgrCtx.close()

    // ── 2. Drive /m as operator ───────────────────────────────────────────────

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)

      // Navigate directly to the count screen.
      const countRef = encodeURIComponent(`COUNT:${countOrder.id}`)
      await op.goto(`${base}/m/count/${countRef}`)
      await op.waitForURL(/\/count\//, { timeout: 10_000 })

      // Phase: location — scan the count location name.
      const locInput = op.getByLabel('Scan location')
      await locInput.waitFor({ timeout: 15_000 })
      await locInput.fill(loc.locationName)
      await locInput.press('Enter')

      // Phase: item — scan the item number (product number = itemDataNumber).
      const itemInput = op.getByLabel('Scan item')
      await itemInput.waitFor({ timeout: 10_000 })
      await itemInput.fill(countLine.itemDataNumber)
      await itemInput.press('Enter')

      // Phase: qty — set the counted quantity (match planned=10 → auto-FINISHED).
      const qtyInput = op.getByLabel('quantity')
      await qtyInput.waitFor({ timeout: 10_000 })
      await qtyInput.fill('10')

      // SUBMIT (single line → this is the last line).
      await op.getByText('SUBMIT', { exact: true }).click()

      // Assert success banner.
      await expect(op.getByText('Count submitted')).toBeVisible({ timeout: 15_000 })
      await op.getByText('BACK TO INBOX').click()
    } finally {
      await opCtx.close()
    }
  })
})
