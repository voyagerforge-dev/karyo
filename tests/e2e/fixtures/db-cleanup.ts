import { execFileSync } from "node:child_process";
import { provisioningTarget } from "./provisioning-target.ts";
import { E2E_PROVISIONED_USERNAMES, requiredCredential } from "./test-data.ts";

/**
 * Direct database cleanup helpers for E2E tests.
 *
 * Tests share one live database, so global setup sweeps `e2e-*`/`E2E-*`
 * artifacts left behind by previous runs (created with unique timestamp
 * suffixes).
 *
 * cleanupSampleData is a manual utility for wiping the demo sample-data set
 * at the DB level. The sample-data specs intentionally do NOT use it -- they
 * verify that loading is idempotent on a dirty database -- but it is handy
 * when a broken run leaves the demo data in a state the app-level reset
 * cannot recover from.
 */

const PG_USER = process.env.KARYO_PG_USER ?? "karyo";
const PG_DB = process.env.KARYO_PG_DB ?? "karyo";
const CONTAINER_CLI = process.env.KARYO_CONTAINER_CLI ?? "podman";

function psql(sql: string): void {
  const container = process.env.KARYO_PG_CONTAINER?.trim();
  if (!container) {
    throw new Error("Database sweep disabled: set KARYO_PG_CONTAINER only to a verified disposable target (and KARYO_CONTAINER_CLI for its runtime)");
  }
  execFileSync(
    CONTAINER_CLI,
    ["exec", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", PG_USER, "-d", PG_DB, "-c", sql],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
}

// Names must match frontend/web/src/features/sample-data/sample-data.ts
const DEMO_LOCATION_NAMES = "'RCV-01','RCV-02','STR-01','STR-02','PCK-01','SHP-01'";
const DEMO_AREA_NAMES = "'Receiving Area A','Storage Area A','Picking Area A','Shipping Area A'";
const DEMO_ZONE_NAMES = "'Receiving Dock','Main Storage','Picking Zone','Shipping Dock'";
const DEMO_ULT_NAMES = "'Standard Pallet','Carton Box'";
const DEMO_LOCATION_TYPE_NAMES = "'Shelf'";

/**
 * Remove ALL demo sample data (reverse dependency order).
 * Manual recovery utility -- see file header. Not used by the specs.
 */
export function cleanupSampleData(): void {
  psql(`
    BEGIN;
    DELETE FROM karyo.stock_units WHERE item_data_number LIKE 'DEMO-%';
    DELETE FROM karyo.unit_loads
      WHERE storage_location_name IN (${DEMO_LOCATION_NAMES})
         OR unit_load_type_id IN (SELECT id FROM karyo.unit_load_types WHERE name IN (${DEMO_ULT_NAMES}));
    DELETE FROM karyo.item_data_numbers
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'DEMO-%');
    DELETE FROM karyo.packaging_units
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'DEMO-%');
    DELETE FROM karyo.inactive_products
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'DEMO-%');
    DELETE FROM karyo.item_data WHERE number LIKE 'DEMO-%';
    DELETE FROM karyo.fix_assignments
      WHERE location_id IN (SELECT id FROM karyo.storage_locations WHERE name IN (${DEMO_LOCATION_NAMES}));
    DELETE FROM karyo.storage_locations WHERE name IN (${DEMO_LOCATION_NAMES});
    DELETE FROM karyo.areas WHERE name IN (${DEMO_AREA_NAMES});
    DELETE FROM karyo.storage_strategies
      WHERE zone_id IN (SELECT id FROM karyo.zones WHERE name IN (${DEMO_ZONE_NAMES}));
    DELETE FROM karyo.zones WHERE name IN (${DEMO_ZONE_NAMES});
    DELETE FROM karyo.unit_load_types WHERE name IN (${DEMO_ULT_NAMES});
    DELETE FROM karyo.location_types WHERE name IN (${DEMO_LOCATION_TYPE_NAMES});
    COMMIT;
  `);
}

/**
 * Sweep entities created by previous E2E runs (unique-suffixed "e2e-" and
 * "E2E-" names). Runs once per suite via global setup; failures non-fatal.
 */
export function cleanupE2EArtifacts(): void {
  psql(`
    BEGIN;
    -- Transport orders (putaway/move/transfer) + their location reservations FIRST: they
    -- reference e2e storage_locations by name, and location_reservations FK
    -- storage_locations ON DELETE CASCADE — so they must go before the locations
    -- below. transport_orders carry no e2e-prefixed order_number (TO/TR-generated),
    -- so sweep by the e2e source/dest/suggested location names they reference.
    --
    -- PT15 self-FK (successor_id, V505): a TRANSFER successor chain-minted by
    -- ChainContinuationService is its OWN row, linked back from its predecessor via
    -- successor_id BIGINT REFERENCES transport_orders(id) (no ON DELETE clause, so
    -- NOT DEFERRABLE / checked at end of statement — the Postgres default). This single
    -- DELETE still removes predecessor + successor together in one statement: every hop
    -- of a chain has its OWN source_location_name set to whichever e2e- location it
    -- departed from (the dock for the first hop, an e2e- transfer-staging location for
    -- every hop after), so each row independently satisfies this WHERE clause via its
    -- own source_location_name — not via a cascade through successor_id. Both rows are
    -- therefore in the SAME delete's row set, and the self-FK is satisfied when checked
    -- at the end of the statement (no dangling successor_id, since the row it points to
    -- is deleted in that same statement). Verified against a live container: seeded a
    -- predecessor/successor pair with successor_id set, ran this exact predicate inside
    -- a transaction, confirmed both rows gone with zero FK errors, then rolled back.
    -- No NULLing of successor_id needed first.
    DELETE FROM karyo.location_reservations
      WHERE transport_order_id IN (
        SELECT id FROM karyo.transport_orders
         WHERE source_location_name LIKE 'e2e-%'
            OR destination_location_name LIKE 'e2e-%'
            OR suggested_location_name LIKE 'e2e-%'
      );
    DELETE FROM karyo.transport_orders
      WHERE source_location_name LIKE 'e2e-%'
         OR destination_location_name LIKE 'e2e-%'
         OR suggested_location_name LIKE 'e2e-%';
    -- Cross-docking (Advanced Fulfillment pack, cross-docking.spec.ts): cross_dock_orders
    -- carries only bare Long ids (goods_receipt_line_id/delivery_order_line_id/unit_load_id/
    -- stock_unit_id), no FK to any table swept above or below -- unlike transport_orders, it is
    -- never referenced by a swept e2e- location name, so without its own rule it would survive
    -- every other sweep here as an orphan once the rows it points at (deleted below) are gone.
    -- Sweep by its own order_number, which CrossDockInterceptor.mintOrder always generates as
    -- "XD-" + the receiving line's id -- nothing in this module accepts a caller-supplied
    -- number, so every row on this (test-only) stack is safe to sweep unconditionally.
    -- Guarded in a DO block: a target stack not yet redeployed with the crossdock migration
    -- (V1300) has no cross_dock_orders table at all, and an unguarded DELETE here would abort
    -- this WHOLE transaction (ON_ERROR_STOP=1) -- rolling back every sweep statement above,
    -- not just this one -- on a stack this spec is meant to skip cleanly on in the first place.
    DO $$
    BEGIN
      EXECUTE 'DELETE FROM karyo.cross_dock_orders WHERE order_number LIKE ''XD-%''';
    EXCEPTION WHEN undefined_table THEN
      NULL;
    END $$;
    -- Wave bulk fulfillment (waves.spec.ts): waves/consolidation_groups/wave-linked pick_orders
    -- carry no e2e-prefixed column of their own -- wave_number is sequence-generated ("W-..."),
    -- batch pickOrderNumber is "WB-<waveId>-..." (neither caller-supplied) -- identified
    -- transitively via delivery_orders.wave_id for e2e-numbered orders, captured HERE (BEFORE the
    -- delivery_orders sweep below removes those rows -- the only remaining thread back to "which
    -- wave is ours"). No FK on wave_id either direction (id-only columns, house rule per
    -- V430/V610's own comments), so each table needs its own explicit DELETE; picks.pick_order_id
    -- DOES carry a real FK to pick_orders (no ON DELETE CASCADE, V601), so picks must go first.
    -- Guarded: a stack not yet redeployed with the wave module's migrations (V1400/V430/V610) has
    -- none of these tables/columns -- same guard idiom as the cross_dock_orders sweep above.
    DO $$
    DECLARE
      wave_ids BIGINT[];
    BEGIN
      SELECT ARRAY_AGG(DISTINCT wave_id) INTO wave_ids
        FROM karyo.delivery_orders
       WHERE (order_number LIKE 'e2e-%' OR order_number LIKE 'E2E-%') AND wave_id IS NOT NULL;
      IF wave_ids IS NOT NULL THEN
        DELETE FROM karyo.picks WHERE pick_order_id IN (
          SELECT id FROM karyo.pick_orders WHERE wave_id = ANY(wave_ids)
        );
        DELETE FROM karyo.pick_orders WHERE wave_id = ANY(wave_ids);
        DELETE FROM karyo.consolidation_groups WHERE wave_id = ANY(wave_ids);
        DELETE FROM karyo.waves WHERE id = ANY(wave_ids);
      END IF;
    EXCEPTION WHEN undefined_table OR undefined_column THEN
      NULL;
    END $$;
    -- Named selection rules (selection-rules sprint, waves.spec.ts Task 6): wave_selection_rules
    -- rows are name-seeded (uniq("e2e-waves-rule") etc.), no FK either direction (waves.selection_
    -- rule_id is an id-only column, same house rule as wave_id above), so a plain name sweep is
    -- enough -- and it must run BEFORE the delivery_orders sweep only in the sense that both are
    -- independent; ordered here for locality with the rest of the wave module's cleanup. Guarded:
    -- a stack not yet redeployed with V1401 has no wave_selection_rules table at all.
    DO $$
    BEGIN
      EXECUTE 'DELETE FROM karyo.wave_selection_rules WHERE name LIKE ''e2e-%''';
    EXCEPTION WHEN undefined_table THEN
      NULL;
    END $$;
    -- Orders first: cascade removes lines + order_line_reservations. Uppercase twin:
    -- sort-station.spec.ts and floor-menu.spec.ts's pack test (Task 6, defect-burndown-7)
    -- both drive createOrder with an ALL-UPPERCASE prefix (uniq('e2e-sort').toUpperCase(),
    -- 'E2E-PKM') so the auto-generated pick-container label stays uppercase-safe for
    -- FreeScanField's case-sensitive lookup -- the resulting order_number is therefore
    -- fully uppercase and the plain 'e2e-%' pattern misses it.
    DELETE FROM karyo.delivery_orders WHERE order_number LIKE 'e2e-%' OR order_number LIKE 'E2E-%';
    -- e2e order strategies (seeded by streaming specs); MUST follow the delivery_orders
    -- delete: delivery_orders.order_strategy_id is a real FK (orders V402), the one
    -- exception to the id-only house rule.
    DELETE FROM karyo.order_strategies WHERE name LIKE 'e2e-%';
    -- Receiving (V424 many-to-many): goods_receipts no longer carries a scalar
    -- asn_id column -- the link now lives in goods_receipt_asns, which FKs BOTH
    -- goods_receipts(id) and asns(id) WITHOUT ON DELETE CASCADE. That join table
    -- must therefore be emptied before EITHER side is deleted. The CTE below
    -- deletes the join rows first (for an e2e-numbered ASN OR an e2e-numbered
    -- receipt) and feeds their goods_receipt_id back via RETURNING so a receipt
    -- carrying only a server-generated receipt number (e.g. opened via the ASN
    -- detail's "Open receipt" button, which sends no receiptNumber) but linked to
    -- an e2e ASN is still swept. asn_ul_advices (V426) FKs asns WITH ON DELETE
    -- CASCADE, so it would clean up on its own, but it's swept explicitly here
    -- too (before the asns delete) for a self-documenting order. The
    -- stock_units/unit_loads goods receipts create carry E2E-/e2e- names and are
    -- removed by the rules further down.
    DELETE FROM karyo.asn_ul_advices
      WHERE asn_id IN (SELECT id FROM karyo.asns WHERE asn_number LIKE 'e2e-%');
    WITH gra_del AS (
      DELETE FROM karyo.goods_receipt_asns
        WHERE asn_id IN (SELECT id FROM karyo.asns WHERE asn_number LIKE 'e2e-%')
           OR goods_receipt_id IN (SELECT id FROM karyo.goods_receipts WHERE receipt_number LIKE 'e2e-%')
      RETURNING goods_receipt_id
    )
    DELETE FROM karyo.goods_receipts
      WHERE receipt_number LIKE 'e2e-%'
         OR id IN (SELECT goods_receipt_id FROM gra_del);
    DELETE FROM karyo.asns WHERE asn_number LIKE 'e2e-%';
    -- A normal SKU can occupy an E2E location (for example after an ad-hoc move).
    -- Delete every stock child of the selected unit loads before deleting those loads;
    -- SKU-prefix-only selection otherwise rolls the whole sweep back on their FK.
    DELETE FROM karyo.stock_units
      WHERE item_data_number LIKE 'E2E-%'
         OR unit_load_id IN (
           SELECT id FROM karyo.unit_loads
             WHERE storage_location_name LIKE 'e2e-%' OR storage_location_name LIKE 'E2E-%'
         );
    -- Uppercase twin (Task 6, defect-burndown-7): floor-menu.spec.ts's ad-hoc-move dest
    -- location and sort-station.spec.ts's whole seeded area set are uppercase-prefixed
    -- (see the storage_locations/areas/unit_load_types/location_types comments below) --
    -- once a unit load is moved there, storage_location_name picks up that uppercase name.
    DELETE FROM karyo.unit_loads WHERE storage_location_name LIKE 'e2e-%' OR storage_location_name LIKE 'E2E-%';
    DELETE FROM karyo.item_data_numbers
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'E2E-%');
    DELETE FROM karyo.packaging_units
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'E2E-%');
    DELETE FROM karyo.inactive_products
      WHERE item_data_id IN (SELECT id FROM karyo.item_data WHERE number LIKE 'E2E-%');
    DELETE FROM karyo.item_data WHERE number LIKE 'E2E-%';
    -- Uppercase twin (Task 6, defect-burndown-7): floor-menu.spec.ts's ad-hoc-move dest
    -- location (uniq('e2e-mvm').toUpperCase() plus '-DEST', forced uppercase so FreeScanField's
    -- case-sensitive lookup matches -- see the file header casing-trap note) and
    -- sort-station.spec.ts's whole uniq('e2e-sort').toUpperCase()-prefixed area set both
    -- land here as 'E2E-%', not 'e2e-%'.
    DELETE FROM karyo.fix_assignments
      WHERE location_id IN (SELECT id FROM karyo.storage_locations WHERE name LIKE 'e2e-%' OR name LIKE 'E2E-%');
    DELETE FROM karyo.storage_locations WHERE name LIKE 'e2e-%' OR name LIKE 'E2E-%';
    DELETE FROM karyo.areas WHERE name LIKE 'e2e-%' OR name LIKE 'E2E-%';
    -- PT15 storage_areas (V311, clusters-as-named-sets, incl. the transfer_staging flag):
    -- had no sweep at all before this task even though transport-chain.spec.ts is the first
    -- spec to create them. storage_area_clusters (the join table) FKs both storage_areas(id)
    -- and location_cluster_id ON DELETE CASCADE, so deleting either side alone already drops
    -- the join rows — order relative to location_clusters below doesn't matter.
    DELETE FROM karyo.storage_areas WHERE name LIKE 'e2e-%';
    DELETE FROM karyo.location_clusters WHERE name LIKE 'e2e-%';
    DELETE FROM karyo.zones WHERE name LIKE 'e2e-%';
    -- Uppercase twin (Task 6, defect-burndown-7): seedAreas' location_type/unit_load_type
    -- names inherit whatever case their caller's prefix uses -- sort-station.spec.ts passes
    -- an all-uppercase prefix, so these are the only two seedAreas-derived tables it needs.
    DELETE FROM karyo.unit_load_types WHERE name LIKE 'e2e-%' OR name LIKE 'E2E-%';
    DELETE FROM karyo.location_types WHERE name LIKE 'e2e-%' OR name LIKE 'E2E-%';
    COMMIT;
  `);
}

const E2E_MARKER = "karyo_e2e";
export const E2E_MANAGED_USERNAME = "e2e-managed-user";

const E2E_IDENTITIES = [
  {
    key: "ADMIN",
    username: E2E_PROVISIONED_USERNAMES.ADMIN,
    email: "admin@e2e.invalid",
    roles: ["ADMIN"],
    clientId: "0",
    tenantCode: "SYS",
  },
  {
    key: "MANAGER",
    username: E2E_PROVISIONED_USERNAMES.MANAGER,
    email: "manager@e2e.invalid",
    roles: ["MANAGER", "integration-admin"],
    clientId: "1",
    tenantCode: "ACME",
    warehouseId: "WH-001",
  },
  {
    key: "OPERATOR",
    username: E2E_PROVISIONED_USERNAMES.OPERATOR,
    email: "operator@e2e.invalid",
    roles: ["OPERATOR"],
    clientId: "1",
    tenantCode: "ACME",
    warehouseId: "WH-001",
  },
  {
    key: "VIEWER",
    username: E2E_PROVISIONED_USERNAMES.VIEWER,
    email: "viewer@e2e.invalid",
    roles: ["VIEWER"],
    clientId: "1",
    tenantCode: "ACME",
  },
] as const;

type KeycloakUserDetail = {
  username?: string;
  attributes?: Record<string, string[]>;
};

type AdminAccess = {
  base: string;
  headers: { Authorization: string };
};

async function keycloakFetch(
  base: string,
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const response = await fetch(`${base}${path}`, { ...init, redirect: "manual" });
  if (response.status >= 300 && response.status < 400) {
    throw new Error(`Keycloak request refused redirect ${response.status}`);
  }
  return response;
}

export function e2eProvisioningEnabled(): boolean {
  return process.env.KARYO_E2E_PROVISION === "true";
}

function requireProvisioningOptIn(): void {
  if (!e2eProvisioningEnabled()) {
    throw new Error("KARYO_E2E_PROVISION=true is required before mutating Keycloak E2E users");
  }
}

async function adminAccess(): Promise<AdminAccess> {
  const base = provisioningTarget();
  requireProvisioningOptIn();
  const secret = requiredCredential(
    "KEYCLOAK_ADMIN_CLIENT_SECRET",
    "KEYCLOAK_ADMIN_CLIENT_SECRET is not set. E2E user provisioning authenticates as the permanent karyo-admin service account and cannot fall back to a retired bootstrap administrator (see docs/guides/implementer-guide.md#run-browser-acceptance).",
  );
  const tokenResponse = await keycloakFetch(base, "/auth/realms/karyo/protocol/openid-connect/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: "karyo-admin",
      client_secret: secret,
      grant_type: "client_credentials",
    }),
  });
  if (!tokenResponse.ok) throw new Error(`KC karyo-admin token ${tokenResponse.status}`);
  const token = ((await tokenResponse.json()) as { access_token?: string }).access_token;
  if (!token) throw new Error("KC karyo-admin token response contained no access token");
  return { base, headers: { Authorization: `Bearer ${token}` } };
}

export function isDisposableE2EUser(user: KeycloakUserDetail): boolean {
  return user.attributes?.[E2E_MARKER]?.[0] === "true" ||
    user.username === E2E_MANAGED_USERNAME;
}

async function reservedE2EUserIds(
  base: string,
  headers: { Authorization: string },
): Promise<string[]> {
  const response = await keycloakFetch(
    base,
    `/auth/admin/realms/karyo/users?username=${encodeURIComponent(E2E_MANAGED_USERNAME)}&exact=true`,
    { headers },
  );
  if (!response.ok) throw new Error(`Keycloak reserved-user lookup ${response.status}`);
  return ((await response.json()) as Array<{ id: string }>).map(({ id }) => id);
}

async function deleteIfDisposable(
  base: string,
  headers: { Authorization: string },
  userId: string,
): Promise<boolean> {
  const detailResponse = await keycloakFetch(
    base,
    `/auth/admin/realms/karyo/users/${userId}`,
    { headers },
  );
  if (!detailResponse.ok) throw new Error(`Keycloak user detail ${detailResponse.status}`);
  if (!isDisposableE2EUser((await detailResponse.json()) as KeycloakUserDetail)) return false;
  const deleteResponse = await keycloakFetch(
    base,
    `/auth/admin/realms/karyo/users/${userId}`,
    { method: "DELETE", headers },
  );
  if (deleteResponse.status !== 204) {
    throw new Error(`Keycloak user deletion ${deleteResponse.status}`);
  }
  return true;
}

export async function cleanupE2EUsers(): Promise<void> {
  const { base, headers } = await adminAccess();
  const candidates = new Set<string>();

  for (let first = 0; ; first += 100) {
    const response = await keycloakFetch(
      base,
      `/auth/admin/realms/karyo/users?q=${encodeURIComponent(`${E2E_MARKER}:true`)}&first=${first}&max=100`,
      { headers },
    );
    if (!response.ok) throw new Error(`Keycloak marked-user lookup ${response.status}`);
    const page = (await response.json()) as Array<{ id: string }>;
    for (const user of page) candidates.add(user.id);
    if (page.length < 100) break;
  }

  for (const id of await reservedE2EUserIds(base, headers)) candidates.add(id);

  let deleted = 0;
  for (const candidate of candidates) {
    if (await deleteIfDisposable(base, headers, candidate)) deleted++;
  }
  if (deleted) console.log(`[e2e-users] Cleaned ${deleted} leftover Keycloak e2e users`);
}

export async function removeManagedE2EUser(): Promise<void> {
  const { base, headers } = await adminAccess();
  for (const id of await reservedE2EUserIds(base, headers)) {
    await deleteIfDisposable(base, headers, id);
  }
}

export async function provisionE2EUsers(): Promise<void> {
  const base = provisioningTarget();
  requireProvisioningOptIn();
  const passwords = Object.fromEntries(
    E2E_IDENTITIES.map(({ key }) => [
      key,
      requiredCredential(`KARYO_E2E_${key}_PASSWORD`),
    ]),
  ) as Record<(typeof E2E_IDENTITIES)[number]["key"], string>;
  const access = await adminAccess();
  const roleRepresentations = new Map<string, unknown>();
  for (const role of new Set(E2E_IDENTITIES.flatMap(({ roles }) => roles))) {
    const response = await keycloakFetch(
      base,
      `/auth/admin/realms/karyo/roles/${encodeURIComponent(role)}`,
      { headers: access.headers },
    );
    if (!response.ok) throw new Error(`Keycloak role ${role} lookup ${response.status}`);
    roleRepresentations.set(role, await response.json());
  }

  for (const identity of E2E_IDENTITIES) {
    const attributes: Record<string, string[]> = {
      [E2E_MARKER]: ["true"],
      client_id: [identity.clientId],
      principal_kind: ["ops"],
      tenant_code: [identity.tenantCode],
    };
    if ("warehouseId" in identity) attributes.warehouse_id = [identity.warehouseId];
    const createResponse = await keycloakFetch(base, "/auth/admin/realms/karyo/users", {
      method: "POST",
      headers: { ...access.headers, "Content-Type": "application/json" },
      body: JSON.stringify({
        username: identity.username,
        email: identity.email,
        firstName: "E2E",
        lastName: identity.key,
        enabled: true,
        attributes,
        credentials: [{ type: "password", value: passwords[identity.key], temporary: false }],
      }),
    });
    if (createResponse.status !== 201) {
      throw new Error(`Keycloak ${identity.key.toLowerCase()} user creation ${createResponse.status}`);
    }
    const lookupResponse = await keycloakFetch(
      base,
      `/auth/admin/realms/karyo/users?username=${encodeURIComponent(identity.username)}&exact=true`,
      { headers: access.headers },
    );
    if (!lookupResponse.ok) throw new Error(`Keycloak user lookup ${lookupResponse.status}`);
    const matches = (await lookupResponse.json()) as Array<{ id: string }>;
    if (matches.length !== 1) {
      throw new Error(`Expected one Keycloak user named ${identity.username}, found ${matches.length}`);
    }
    const mappingResponse = await keycloakFetch(
      base,
      `/auth/admin/realms/karyo/users/${matches[0].id}/role-mappings/realm`,
      {
        method: "POST",
        headers: { ...access.headers, "Content-Type": "application/json" },
        body: JSON.stringify(identity.roles.map((role) => roleRepresentations.get(role))),
      },
    );
    if (mappingResponse.status !== 204) {
      throw new Error(`Keycloak ${identity.key.toLowerCase()} role mapping ${mappingResponse.status}`);
    }
    process.env[`KARYO_E2E_${identity.key}_USER`] = identity.username;
  }
  console.log(`[e2e-users] Provisioned ${E2E_IDENTITIES.length} ephemeral Keycloak users`);
}

export async function logoutE2EUserSessions(username: string): Promise<void> {
  const { base, headers } = await adminAccess();
  const usersResponse = await keycloakFetch(
    base,
    `/auth/admin/realms/karyo/users?username=${encodeURIComponent(username)}&exact=true`,
    { headers },
  );
  if (!usersResponse.ok) throw new Error(`Keycloak user lookup ${usersResponse.status}`);
  const users = (await usersResponse.json()) as Array<{ id: string }>;
  if (users.length !== 1) {
    throw new Error(`Expected one Keycloak user named ${username}, found ${users.length}`);
  }
  const logoutResponse = await keycloakFetch(
    base,
    `/auth/admin/realms/karyo/users/${users[0].id}/logout`,
    { method: "POST", headers },
  );
  if (logoutResponse.status !== 204) {
    throw new Error(`Keycloak session logout ${logoutResponse.status}`);
  }
}
