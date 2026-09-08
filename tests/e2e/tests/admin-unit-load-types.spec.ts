/**
 * Admin unit load types E2E -- Task 9 (stock-and-orders sprint), covering
 * Task 5's `/admin/unit-load-types` page.
 *
 * Read `shipping.spec.ts` and `admin-documents.spec.ts` first for the
 * established shape this mirrors: `admin-documents.spec.ts` is the closest
 * analog -- another `/admin/*` page gated by AdminGuard's `user-admin`
 * permission, which only the seeded `admin` principal holds (`manager` and
 * `operator` both 403/redirect out). The default `authenticatedPage`
 * fixture already logs in as `admin` (fixtures/auth.ts's default
 * `credentials`), so no second browser context is needed here.
 *
 * The page's own KDoc (admin-unit-load-types-page.tsx) calls out that
 * `PUT /api/v1/unit-load-types/{id}` is a FULL-REPRESENTATION update: any
 * field the request omits is reset to its backend default, not left alone.
 * The edit form always resubmits every field from its own local state for
 * exactly this reason. This spec's core assertion is that promise: create a
 * type with every numeric field populated, then edit ONLY the `manageEmpties`
 * switch and save -- the sibling fields (height/width/depth/liftingCapacity/
 * weight/usages/name) must survive the round trip untouched.
 *
 * Unique e2e-ult-* names per run keep the spec re-runnable; global-setup
 * sweeps e2e-/E2E- prefixed artifacts from previous runs.
 */
import { test, expect } from "../fixtures/auth";

test.describe("Admin: unit load types", () => {
  test("create a type, then a full-representation edit toggles manageEmpties without dropping sibling fields", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

    const suffix = Date.now();
    const name = `e2e-ult-${suffix}`;

    await page.goto("/admin/unit-load-types");
    await expect(page.getByTestId("admin-unit-load-types-page")).toBeVisible();

    // --- Create, with every numeric field populated + manageEmpties left OFF ---

    await page.getByRole("button", { name: /new type/i }).click();
    await page.getByLabel("Name").fill(name);
    await page.getByLabel("Height").fill("150");
    await page.getByLabel("Width").fill("120");
    await page.getByLabel("Depth").fill("100");
    await page.getByLabel("Lifting capacity").fill("800");
    await page.getByLabel("Weight (empty container)").fill("22");
    await page.getByLabel("Usages").fill("PALLET");

    const createResponsePromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === "/api/v1/unit-load-types",
    );
    await page.getByRole("button", { name: /^create$/i }).click();
    const created = (await (await createResponsePromise).json()) as {
      id: number;
      manageEmpties: boolean;
    };
    expect(created.manageEmpties).toBe(false);

    // --- Select the new type; assert the read side shows every field it was created with ---
    // The list's MasterList row cap truncates once the persistent :8088 DB has enough seeded
    // unit load types (94+ at last count -- floor-menu specs seed inq-*/mvm-* names the global
    // e2e-*/E2E-* cleanup sweep never matches), so the freshly-created row can sit past the
    // visible limit. Narrow the list to just this row via its own search box first.

    await page.getByPlaceholder("Search unit load types…").fill(name);

    const row = page.locator("button").filter({ hasText: name });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(page.getByRole("heading", { name })).toBeVisible();

    // Scoped to the detail pane's own section, not the list -- every row in the
    // master list also carries a "Manage empties: Off"/"On" pill (the same text
    // this page uses for its own StatusPill), so an unscoped getByText would hit
    // a strict-mode violation across every OTHER seeded unit load type too.
    const detailSection = page.locator("section", {
      has: page.getByRole("heading", { name, exact: true }),
    });
    await expect(
      detailSection.getByText("Manage empties: Off", { exact: true }),
    ).toBeVisible();

    // --- Edit: flip ONLY the manageEmpties switch, leave every other field alone ---

    await page.getByRole("button", { name: /^edit$/i }).click();
    await page.getByLabel("Manage empties").click();

    const updateResponsePromise = page.waitForResponse(
      (res) =>
        res.request().method() === "PUT" &&
        new URL(res.url()).pathname === `/api/v1/unit-load-types/${created.id}`,
    );
    await page.getByRole("button", { name: /^save$/i }).click();
    const updated = (await (await updateResponsePromise).json()) as {
      name: string;
      usages: string | null;
      height: number | null;
      width: number | null;
      depth: number | null;
      liftingCapacity: number | null;
      weight: number | null;
      manageEmpties: boolean;
    };

    // The toggle flipped...
    expect(updated.manageEmpties).toBe(true);
    // ...and the full-representation PUT did not reset the sibling fields the
    // form itself never touched -- this is the whole reason
    // UpdateUnitLoadTypeRequest makes every field required instead of optional.
    expect(updated.name).toBe(name);
    expect(updated.usages).toBe("PALLET");
    expect(updated.height).toBe(150);
    expect(updated.width).toBe(120);
    expect(updated.depth).toBe(100);
    expect(updated.liftingCapacity).toBe(800);
    expect(updated.weight).toBe(22);

    // --- The detail pane reflects the flip immediately (React Query invalidation, no reload) ---

    await expect(detailSection.getByText("Manage empties: On", { exact: true })).toBeVisible({
      timeout: 15_000,
    });

    // --- A fresh navigation + re-read proves this is a persisted server-side
    // read, not just an optimistic local echo -- same pattern as the page's
    // own vitest coverage ("updates manageEmpties ... and re-reads the list"). ---

    await page.goto("/admin/unit-load-types");
    await expect(page.getByTestId("admin-unit-load-types-page")).toBeVisible();
    // Fresh navigation resets the search box, so the same row-cap truncation this spec
    // worked around above applies again here -- re-narrow before asserting the row.
    await page.getByPlaceholder("Search unit load types…").fill(name);
    const rowAfterReload = page.locator("button").filter({ hasText: name });
    await expect(
      rowAfterReload.getByText("Manage empties: On", { exact: true }),
    ).toBeVisible({ timeout: 15_000 });
  });
});
