/**
 * Floor PWA menu transactions E2E - floor-menu sprint (Task 9), capstone spec.
 *
 * Covers the 4 scenarios from the task-9 brief: Inquiry, Ad-hoc move (which also
 * re-verifies via Inquiry), Reprint's no-printer degradation, and Pack. Receive
 * and Count's menu handoffs (/receive-select, /adhoc-count) are deliberately NOT
 * re-tested end-to-end here -- their execution screens (/receive/:ref, /count/:ref)
 * already have full E2E coverage in floor-receive.spec.ts / floor-move-count.spec.ts,
 * and the selection logic itself (list/scan open ASNs, find-or-create receipt,
 * create-session navigation) is covered by unit tests.
 *
 * Same two-context convention as the other floor-*.spec.ts files:
 *   mgrCtx  → Keycloak manager login → bearer token capture → API seeding
 *   opCtx   → Keycloak operator login → /m PWA → menu transaction flow
 *
 * Casing trap (menu-api.test.ts / free-scan-field.tsx): FreeScanField normalizes
 * every typed/scanned value to uppercase (`norm = s => s.trim().toUpperCase()`)
 * BEFORE calling the menu API's by-label / by-scan-code lookups, both of which are
 * case-sensitive exact matches server-side (`find("labelId", labelId)` /
 * `scanCode = ?1`). Any identifier this spec types into a FreeScanField must
 * already be stored uppercase, or the lookup 404s. `seedStockOnUL`'s unit-load
 * labels and `seedProduct`'s SKUs are already uppercase (both call
 * `uniq(prefix).toUpperCase()` internally) -- safe as-is. Two places are NOT safe
 * by default and are handled explicitly below: the ad-hoc-move destination
 * location (created by this spec, so its name/scanCode are forced uppercase), and
 * the pack test's auto-generated pick-container label (`PO-${order.orderNumber}`,
 * where `order.orderNumber = uniq(prefix)` is NOT auto-uppercased by
 * `createOrder`/`driveToPicked` -- fixed by passing an uppercase prefix so the
 * whole generated orderNumber, and therefore the container label built from it,
 * comes out uppercase).
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
  driveToPicked,
  post,
  get,
  uniq,
  type Entity,
} from '../fixtures/scenario-helpers'

type UnitLoadInfo = { id: number; labelId: string; storageLocationName: string }

test.describe('Floor PWA - menu', () => {
  test('menu: inquiry on a seeded unit load', async ({ baseURL, browser }) => {
    test.setTimeout(60_000)
    const base = baseURL || "http://localhost"

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, h, 'e2e-inq')
    const product = await seedProduct(mgrPage, h, 'e2e-inq')
    const stock = await seedStockOnUL(mgrPage, h, { product, areas, amount: 5, prefix: 'e2e-inq' })
    const ul = await get<UnitLoadInfo>(mgrPage, `/api/v1/unit-loads/${stock.unitLoadId}`, h)
    await mgrCtx.close()

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)
      await op.goto(`${base}/m/menu`)
      await op.getByRole('button').filter({ hasText: 'Inquiry' }).first().click()
      await op.waitForURL(/\/inquiry/, { timeout: 10_000 })

      const scanInput = op.getByLabel('Scan unit load or location')
      await scanInput.waitFor({ timeout: 10_000 })
      await scanInput.fill(ul.labelId)
      await scanInput.press('Enter')

      // The label + a stock line (item number) render.
      await expect(op.getByText(ul.labelId, { exact: true })).toBeVisible({ timeout: 10_000 })
      await expect(op.getByText(product.number)).toBeVisible()
    } finally {
      await opCtx.close()
    }
  })

  test('menu: ad-hoc move updates the unit load location', async ({ baseURL, browser }) => {
    test.setTimeout(90_000)
    const base = baseURL || "http://localhost"

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, h, 'e2e-mvm')
    const product = await seedProduct(mgrPage, h, 'e2e-mvm')
    const stock = await seedStockOnUL(mgrPage, h, { product, areas, amount: 5, prefix: 'e2e-mvm' })
    const ul = await get<UnitLoadInfo>(mgrPage, `/api/v1/unit-loads/${stock.unitLoadId}`, h)

    // Destination location, forced uppercase (see the casing-trap note at the top
    // of this file) -- a second location in the same STORAGE area as the source.
    // Uppercase means it lands as 'E2E-MVM-...-DEST', not 'e2e-mvm-...-dest' -- db-cleanup.ts's
    // storage_locations/unit_loads sweep arms carry an 'E2E-%' twin precisely for this row.
    const destLocName = `${uniq('e2e-mvm').toUpperCase()}-DEST`
    await post<Entity>(mgrPage, '/api/v1/locations', {
      name: destLocName,
      scanCode: destLocName,
      locationTypeId: areas.locationTypeId,
      areaId: areas.storeAreaId,
    }, h)
    await mgrCtx.close()

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)

      // ── Precondition: Inquiry shows the UL at its seeded source location ────
      await op.goto(`${base}/m/menu`)
      await op.getByRole('button').filter({ hasText: 'Inquiry' }).first().click()
      await op.waitForURL(/\/inquiry/, { timeout: 10_000 })
      let scanInput = op.getByLabel('Scan unit load or location')
      await scanInput.waitFor({ timeout: 10_000 })
      await scanInput.fill(ul.labelId)
      await scanInput.press('Enter')
      await expect(op.getByText(areas.storeLocName)).toBeVisible({ timeout: 10_000 })

      // ── Move: scan the UL, scan the target location, confirm ────────────────
      await op.getByRole('button', { name: 'Back to menu', exact: true }).click()
      await op.getByRole('button').filter({ hasText: 'Move' }).first().click()
      await op.waitForURL(/\/adhoc-move/, { timeout: 10_000 })

      const sourceInput = op.getByLabel('Scan unit load')
      await sourceInput.waitFor({ timeout: 10_000 })
      await sourceInput.fill(ul.labelId)
      await sourceInput.press('Enter')

      const targetInput = op.getByLabel('Scan target location')
      await targetInput.waitFor({ timeout: 10_000 })
      await targetInput.fill(destLocName)
      await targetInput.press('Enter')

      await op.getByRole('button', { name: 'CONFIRM MOVE', exact: true }).click()
      await expect(op.getByText(`Moved ${ul.labelId} to ${destLocName}.`)).toBeVisible({ timeout: 15_000 })

      // ── Re-run Inquiry: the new location renders ─────────────────────────────
      await op.getByRole('button', { name: 'Back to menu', exact: true }).click()
      await op.getByRole('button').filter({ hasText: 'Inquiry' }).first().click()
      await op.waitForURL(/\/inquiry/, { timeout: 10_000 })
      scanInput = op.getByLabel('Scan unit load or location')
      await scanInput.waitFor({ timeout: 10_000 })
      await scanInput.fill(ul.labelId)
      await scanInput.press('Enter')
      await expect(op.getByText(destLocName)).toBeVisible({ timeout: 10_000 })
    } finally {
      await opCtx.close()
    }
  })

  test('menu: reprint surfaces the no-printer problem cleanly', async ({ baseURL, browser }) => {
    test.setTimeout(60_000)
    const base = baseURL || "http://localhost"

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, h, 'e2e-rpt')
    const product = await seedProduct(mgrPage, h, 'e2e-rpt')
    const stock = await seedStockOnUL(mgrPage, h, { product, areas, amount: 5, prefix: 'e2e-rpt' })
    const ul = await get<UnitLoadInfo>(mgrPage, `/api/v1/unit-loads/${stock.unitLoadId}`, h)
    await mgrCtx.close()

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)
      await op.goto(`${base}/m/menu`)
      await op.getByRole('button').filter({ hasText: 'Reprint' }).first().click()
      await op.waitForURL(/\/reprint/, { timeout: 10_000 })

      const scanInput = op.getByLabel('Scan unit load')
      await scanInput.waitFor({ timeout: 10_000 })
      await scanInput.fill(ul.labelId)
      await scanInput.press('Enter')

      await op.getByRole('button', { name: 'PRINT', exact: true }).click()

      // The stack has no KARYO_PRINT_URL (unset -> the backend's sentinel default
      // "none"), so the print call 503s as LabelPrintService.NoPrinterConfigured.
      // The screen toasts `e.problem.detail`, NOT `e.problem.title` -- the title is
      // "No printer configured", but the rendered toast text is the mapper's detail
      // field, "set KARYO_PRINT_URL to host:port of a ZPL printer" (see
      // LabelPrintExceptionMapper.kt). Assert what actually renders.
      await expect(op.getByText('set KARYO_PRINT_URL to host:port of a ZPL printer')).toBeVisible({ timeout: 10_000 })

      // No crash: the scanned unit load card is still shown, screen still usable.
      await expect(op.getByText(ul.labelId, { exact: true })).toBeVisible()
    } finally {
      await opCtx.close()
    }
  })

  test('menu: pack one seeded picked unit load', async ({ baseURL, browser }) => {
    test.setTimeout(90_000)
    const base = baseURL || "http://localhost"

    const mgrCtx = await browser.newContext({ baseURL: base })
    const mgrPage = await mgrCtx.newPage()
    await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password)
    const mgrAuth = await captureAuthHeader(mgrPage)
    const h = { Authorization: mgrAuth }

    const areas = await seedAreas(mgrPage, h, 'e2e-pkm')
    // Uppercase prefix (see the casing-trap note at the top of this file): the pick
    // order's auto-generated container unit-load is labelled `PO-${order.orderNumber}`,
    // and order.orderNumber (from createOrder -> uniq(prefix)) is NOT auto-uppercased
    // the way seedProduct/seedStockOnUL's identifiers are -- an uppercase prefix here
    // is what keeps the whole generated label uppercase-safe for FreeScanField. Given
    // the sweep pattern for e2e- entities, the all-uppercase form of that prefix is
    // 'E2E-PKM' (not bare 'PKM') so the product/stock/order rows it drives land on the
    // 'E2E-%' arms db-cleanup.ts already sweeps item_data/stock_units/delivery_orders with.
    const { pickOrder } = await driveToPicked(mgrPage, h, { areas, amount: 8, prefix: 'E2E-PKM' })
    const container = await get<UnitLoadInfo>(mgrPage, `/api/v1/unit-loads/${pickOrder.targetUnitLoadId}`, h)
    await mgrCtx.close()

    const opCtx = await browser.newContext()
    const op = await opCtx.newPage()
    try {
      await mobileLogin(op, base, TEST_DATA.operator.username, TEST_DATA.operator.password)
      await op.goto(`${base}/m/menu`)
      await op.getByRole('button').filter({ hasText: 'Pack' }).first().click()
      await op.waitForURL(/\/pack/, { timeout: 10_000 })

      const scanInput = op.getByLabel('Scan picked unit load')
      await scanInput.waitFor({ timeout: 10_000 })
      await scanInput.fill(container.labelId)
      await scanInput.press('Enter')

      const weightInput = op.getByLabel('Weight (kg)')
      await weightInput.waitFor({ timeout: 10_000 })
      await weightInput.fill('3.5')

      // CARTON is already the default selection; PACK still needs an explicit click.
      await op.getByRole('button', { name: 'CARTON', exact: true }).click()
      await op.getByRole('button', { name: 'PACK', exact: true }).click()

      await expect(op.getByText(`Packed ${container.labelId} as CARTON.`)).toBeVisible({ timeout: 15_000 })
    } finally {
      await opCtx.close()
    }
  })
})
