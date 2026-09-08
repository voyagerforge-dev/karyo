/**
 * Shared helpers + seeders for the 20-scenario acceptance suite
 * (tests/scenarios.spec.ts).
 *
 * Everything is API-driven (page.request.* with the captured bearer header) so a
 * scenario can drive the full cross-module flow without going through the UI.
 *
 * Request/response shapes were mined from the working picking/packing/shipping
 * specs and verified against the backend resources + DTOs:
 *   - orders:   CreateDeliveryOrderRequest, DeliveryOrderResponse (+ line.shortage/reservedAmount)
 *   - orders:   CreateAsnRequest, CreateGoodsReceiptRequest, ReceiveLineRequest, CreateOrderStrategyRequest
 *     (backing seedAsn/seedGoodsReceipt/receiveLine below -- ASN/receipt DTOs were mined here
 *     well before those helpers existed, hence the split; goods_receipts is V424 many-to-many:
 *     CreateGoodsReceiptRequest.asnIds, GoodsReceiptResponse.asns[])
 *   - inventory: stock-units create + /change-state {state:Int}
 *   - layout:   location-types, areas (usages[]), locations, find-putaway (internal)
 *   - product:  products, item-substitutions
 *   - fulfillment: pick-orders, picks/{id}/confirm, shipments (+pack/manifest/dispatch), docs
 *
 * Seeders use a per-call timestamped + random suffix so every scenario seeds its
 * own uniquely-named data and the suite is order-insensitive. Areas accumulate in
 * the shared DB; findPackStaging/findShipStaging take the first match, which is fine.
 */
import { expect, type APIResponse, type Page } from "@playwright/test";

export const UNDEFINED = 0;
export const INCOMING = 100;
export const ON_STOCK = 300;
export const PICKED = 600;

/** Unique-ish suffix for seeded entity names. */
export function uniq(prefix = "e2e"): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

export type Entity = { id: number };
export type Headers = Record<string, string>;

// --- request helpers -------------------------------------------------------

async function expectOk(res: APIResponse, label: string): Promise<void> {
  expect(res.ok(), `${label} failed: ${res.status()} ${await res.text()}`).toBe(
    true,
  );
}

/** POST that asserts 2xx and returns the parsed JSON body. */
export async function post<T>(
  page: Page,
  path: string,
  data: unknown,
  headers: Headers,
): Promise<T> {
  const res = await page.request.post(path, { headers, data });
  await expectOk(res, `POST ${path}`);
  return (await res.json()) as T;
}

/** GET that asserts 2xx and returns the parsed JSON body. */
export async function get<T>(
  page: Page,
  path: string,
  headers: Headers,
): Promise<T> {
  const res = await page.request.get(path, { headers });
  await expectOk(res, `GET ${path}`);
  return (await res.json()) as T;
}

/** DELETE that asserts 2xx (204/200). */
export async function del(
  page: Page,
  path: string,
  headers: Headers,
): Promise<void> {
  const res = await page.request.delete(path, { headers });
  await expectOk(res, `DELETE ${path}`);
}

/**
 * Raw POST that NEVER throws on non-2xx -- returns the APIResponse so a test can
 * assert an expected failure status (401/403/409/422). Use for guard scenarios.
 */
export async function rawPost(
  page: Page,
  path: string,
  data: unknown,
  headers: Headers,
): Promise<APIResponse> {
  return page.request.post(path, { headers, data, failOnStatusCode: false });
}

/** Raw GET that never throws on non-2xx -- for asserting status codes. */
export async function rawGet(
  page: Page,
  path: string,
  headers: Headers,
): Promise<APIResponse> {
  return page.request.get(path, { headers, failOnStatusCode: false });
}

// --- seeders ---------------------------------------------------------------

/** The seeded set of staging/storage areas + locations + a unit-load-type. */
export type Areas = {
  storeAreaId: number;
  storeLocId: number;
  storeLocName: string;
  packAreaId: number;
  shipAreaId: number;
  locationTypeId: number;
  unitLoadTypeId: number;
};

/**
 * Seeds a location-type, a unit-load-type, and STORAGE + PACK_STAGING + SHIP_STAGING
 * areas (each with one location). Returns the ids the pick/pack/ship flows need.
 */
export async function seedAreas(
  page: Page,
  headers: Headers,
  prefix = "areas",
): Promise<Areas> {
  const s = uniq(prefix);

  const locationType = await post<Entity>(
    page,
    "/api/v1/location-types",
    {
      name: `${s}-shelf`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    },
    headers,
  );

  const unitLoadType = await post<Entity>(
    page,
    "/api/v1/unit-load-types",
    {
      name: `${s}-pallet`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    },
    headers,
  );

  const packArea = await post<Entity>(
    page,
    "/api/v1/areas",
    { name: `${s}-pack`, usages: ["PACK_STAGING"] },
    headers,
  );
  const packLocName = `${s}-packloc`;
  await post(
    page,
    "/api/v1/locations",
    {
      name: packLocName,
      scanCode: packLocName,
      locationTypeId: locationType.id,
      areaId: packArea.id,
    },
    headers,
  );

  const shipArea = await post<Entity>(
    page,
    "/api/v1/areas",
    { name: `${s}-ship`, usages: ["SHIP_STAGING"] },
    headers,
  );
  const shipLocName = `${s}-shiploc`;
  await post(
    page,
    "/api/v1/locations",
    {
      name: shipLocName,
      scanCode: shipLocName,
      locationTypeId: locationType.id,
      areaId: shipArea.id,
    },
    headers,
  );

  const storeArea = await post<Entity>(
    page,
    "/api/v1/areas",
    { name: `${s}-store`, usages: ["STORAGE"] },
    headers,
  );
  const storeLocName = `${s}-storeloc`;
  const storeLoc = await post<Entity>(
    page,
    "/api/v1/locations",
    {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    },
    headers,
  );

  return {
    storeAreaId: storeArea.id,
    storeLocId: storeLoc.id,
    storeLocName,
    packAreaId: packArea.id,
    shipAreaId: shipArea.id,
    locationTypeId: locationType.id,
    unitLoadTypeId: unitLoadType.id,
  };
}

export type Product = { id: number; number: string };

/** Seeds a product (using the first seeded item-unit). */
export async function seedProduct(
  page: Page,
  headers: Headers,
  prefix = "prod",
): Promise<Product> {
  const itemUnits = await get<Entity[]>(page, "/api/v1/item-units", headers);
  expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);
  const sku = uniq(prefix).toUpperCase();
  const product = await post<Entity>(
    page,
    "/api/v1/products",
    {
      number: sku,
      name: `${prefix}-${sku}`,
      description: "scenario product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    },
    headers,
  );
  return { id: product.id, number: sku };
}

export type StockSeed = { stockUnitId: number; unitLoadId: number };

/**
 * Seeds a unit-load at the storage location + a stock unit of `amount` for the
 * given product, then advances it to `state` (default ON_STOCK). Created stock is
 * advanced through INCOMING -> ON_STOCK via /change-state (the forward-only path).
 */
export async function seedStockOnUL(
  page: Page,
  headers: Headers,
  args: {
    product: Product;
    areas: Areas;
    amount: number;
    state?: number;
    prefix?: string;
  },
): Promise<StockSeed> {
  const { product, areas, amount } = args;
  const state = args.state ?? ON_STOCK;
  const s = uniq(args.prefix ?? "stk").toUpperCase();

  const ul = await post<Entity>(
    page,
    "/api/v1/unit-loads",
    {
      clientId: 1,
      labelId: `${s}-UL`,
      unitLoadTypeId: areas.unitLoadTypeId,
      storageLocationId: areas.storeLocId,
      storageLocationName: areas.storeLocName,
    },
    headers,
  );
  const stock = await post<Entity>(
    page,
    "/api/v1/stock-units",
    {
      itemDataId: product.id,
      itemDataNumber: product.number,
      amount,
      unitLoadId: ul.id,
      state: INCOMING,
    },
    headers,
  );
  if (state !== INCOMING) {
    await post(
      page,
      `/api/v1/stock-units/${stock.id}/change-state`,
      { state },
      headers,
    );
  }
  return { stockUnitId: stock.id, unitLoadId: ul.id };
}

export type OrderLine = { itemDataId: number; itemDataNumber: string; amount: number };
export type DeliveryOrder = {
  id: number;
  clientId: number;
  orderNumber: string;
  state: number;
  stateName: string;
  orderStrategyId: number | null;
  lines: Array<{
    id: number;
    itemDataId: number;
    amount: string | number;
    reservedAmount: string | number;
    shortage: string | number;
    state: number;
  }>;
};

/** Creates a delivery order (optionally referencing an order strategy). */
export async function createOrder(
  page: Page,
  headers: Headers,
  args: {
    lines: OrderLine[];
    orderStrategyId?: number;
    customerName?: string;
    prefix?: string;
    address?: Record<string, string>;
  },
): Promise<DeliveryOrder> {
  const orderNumber = uniq(args.prefix ?? "ord");
  return post<DeliveryOrder>(
    page,
    "/api/v1/delivery-orders",
    {
      orderNumber,
      customerName: args.customerName ?? "Scenario Customer",
      orderStrategyId: args.orderStrategyId,
      lines: args.lines,
      ...(args.address ?? {}),
    },
    headers,
  );
}

/**
 * Releases an order (reserves stock). The release endpoint returns
 * DeliveryOrderReleaseResponse `{ order, shortages }`; this unwraps `.order` so
 * callers see the post-release DeliveryOrderResponse (with its lines).
 */
export async function releaseOrder(
  page: Page,
  headers: Headers,
  orderId: number,
): Promise<DeliveryOrder> {
  const res = await post<{ order: DeliveryOrder; shortages: unknown[] }>(
    page,
    `/api/v1/delivery-orders/${orderId}/release`,
    {},
    headers,
  );
  return res.order;
}

export type PickResponse = {
  id: number;
  plannedAmount: string | number;
  pickedAmount: string | number;
  state: number;
  followUpForPickId: number | null;
  substitutedItemDataId: number | null;
};
export type PickOrder = {
  id: number;
  pickOrderNumber: string;
  deliveryOrderId: number;
  state: number;
  targetUnitLoadId: number | null;
  picks: PickResponse[];
};

/**
 * Releases an order to picking, returning the created PickOrder + its picks.
 *
 * Register row 8: POST /api/v1/pick-orders always returns a JSON array now
 * (releaseToPicking can mint more than one PickOrder per delivery order via
 * createTypeOrders). None of this suite's scenarios enable createTypeOrders
 * (it defaults false, migration V428) or use a custom wave/batch grouping
 * strategy (DiscreteGroupingStrategy -- one DeliveryOrder -> one PickOrder --
 * is the only one shipped), so exactly one PickOrder comes back for every
 * caller of this helper; asserting that here catches a real regression
 * instead of silently returning the wrong element.
 */
export async function releaseToPicking(
  page: Page,
  headers: Headers,
  orderId: number,
): Promise<PickOrder> {
  const pickOrders = await post<PickOrder[]>(
    page,
    "/api/v1/pick-orders",
    { deliveryOrderId: orderId },
    headers,
  );
  expect(pickOrders.length).toBe(1);
  return pickOrders[0];
}

/** GET the latest PickOrder state + picks. */
export async function getPickOrder(
  page: Page,
  headers: Headers,
  pickOrderId: number,
): Promise<PickOrder> {
  return get<PickOrder>(page, `/api/v1/pick-orders/${pickOrderId}`, headers);
}

/** Confirms one pick at `amount` (full-qty by default = its plannedAmount). */
export async function confirmPick(
  page: Page,
  headers: Headers,
  pickId: number,
  pickedAmount: number,
): Promise<PickResponse> {
  return post<PickResponse>(
    page,
    `/api/v1/picks/${pickId}/confirm`,
    { pickedAmount },
    headers,
  );
}

/** GET an order's current state. */
export async function getOrder(
  page: Page,
  headers: Headers,
  orderId: number,
): Promise<DeliveryOrder> {
  return get<DeliveryOrder>(page, `/api/v1/delivery-orders/${orderId}`, headers);
}

/**
 * Drives a fresh order all the way to PICKED: seed product + stock, create + release
 * the order, release to picking, confirm every pick at its planned (full) amount.
 * Returns the order + pick-order ids for downstream pack/ship steps.
 */
export async function driveToPicked(
  page: Page,
  headers: Headers,
  args: { areas: Areas; amount?: number; prefix?: string },
): Promise<{ order: DeliveryOrder; pickOrder: PickOrder; product: Product }> {
  const amount = args.amount ?? 60;
  const product = await seedProduct(page, headers, args.prefix ?? "drive");
  await seedStockOnUL(page, headers, {
    product,
    areas: args.areas,
    amount,
    prefix: args.prefix,
  });
  const order = await createOrder(page, headers, {
    lines: [{ itemDataId: product.id, itemDataNumber: product.number, amount }],
    prefix: args.prefix,
  });
  await releaseOrder(page, headers, order.id);
  const pickOrder = await releaseToPicking(page, headers, order.id);
  for (const pick of pickOrder.picks) {
    await confirmPick(page, headers, pick.id, Number(pick.plannedAmount));
  }
  return { order, pickOrder, product };
}

export type Shipment = {
  id: number;
  shipmentNumber: string;
  deliveryOrderId: number;
  state: number;
  carrierName: string | null;
  carrierService: string | null;
  trackingNumber: string | null;
  shippingUnits: Array<{ id: number; state: number; type: string; unitLoadId: number | null }>;
};

/** Opens packing for an order (Shipment PACKING 640). */
export async function openPacking(
  page: Page,
  headers: Headers,
  orderId: number,
): Promise<Shipment> {
  return post<Shipment>(
    page,
    "/api/v1/shipments",
    { deliveryOrderId: orderId },
    headers,
  );
}

// ── Replenishment helpers ──────────────────────────────────────────────────

export type FixAssignment = {
  id: number;
  locationId: number;
  locationName: string;
  itemDataId: number;
  itemDataNumber: string | null;
  minAmount: string | number | null;
  maxAmount: string | number | null;
  desiredAmount: string | number | null;
  currentStockAmount: string | number | null;
  orderIndex: number;
};

/**
 * Seeds a fix-assignment (min-qty slotting rule) on a pick-face location for a product.
 * Field names mirror `CreateFixAssignmentRequest`:
 *   locationId, itemDataId, minAmount, maxAmount?, desiredAmount?, orderIndex?
 */
export async function seedFixAssignment(
  page: Page,
  headers: Headers,
  args: {
    locationId: number;
    itemDataId: number;
    minAmount: number;
    maxAmount?: number;
    desiredAmount?: number;
    orderIndex?: number;
  },
): Promise<FixAssignment> {
  return post<FixAssignment>(
    page,
    "/api/v1/fix-assignments",
    {
      locationId: args.locationId,
      itemDataId: args.itemDataId,
      minAmount: args.minAmount,
      maxAmount: args.maxAmount ?? null,
      desiredAmount: args.desiredAmount ?? null,
      orderIndex: args.orderIndex ?? 0,
    },
    headers,
  );
}

/** Packs a shipment at `weight` (PACKING 640 -> PACKED 650). */
export async function packShipment(
  page: Page,
  headers: Headers,
  shipmentId: number,
  weight = 2.5,
): Promise<Shipment> {
  return post<Shipment>(
    page,
    `/api/v1/shipments/${shipmentId}/pack`,
    { weight },
    headers,
  );
}

/**
 * Drives a fresh order to a PACKED shipment (driveToPicked -> open packing -> pack).
 * Returns the order, shipment and product.
 */
export async function driveToPacked(
  page: Page,
  headers: Headers,
  args: { areas: Areas; amount?: number; prefix?: string },
): Promise<{ order: DeliveryOrder; shipment: Shipment; product: Product }> {
  const { order, product } = await driveToPicked(page, headers, args);
  const opened = await openPacking(page, headers, order.id);
  const shipment = await packShipment(page, headers, opened.id);
  return { order, shipment, product };
}

// ── Cycle-count helpers ────────────────────────────────────────────────────

export type CountLocation = {
  locationId: number;
  locationName: string;
  locationTypeId: number;
};

/**
 * Seeds a minimal STORAGE area + location for cycle-count scenarios.
 * Returns just the location id/name (and the locationTypeId so callers can reuse it
 * for additional locations without creating a duplicate location-type).
 */
export async function seedCountLocation(
  page: Page,
  headers: Headers,
  args: { prefix?: string; locationTypeId?: number; areaUsage?: string },
): Promise<CountLocation> {
  const s = uniq(args.prefix ?? "cnt");
  const usage = args.areaUsage ?? "STORAGE";

  const locationTypeId =
    args.locationTypeId ??
    (
      await post<Entity>(
        page,
        "/api/v1/location-types",
        {
          name: `${s}-shelf`,
          height: 200,
          width: 100,
          depth: 120,
          liftingCapacity: 500,
        },
        headers,
      )
    ).id;

  const area = await post<Entity>(
    page,
    "/api/v1/areas",
    { name: `${s}-area`, usages: [usage] },
    headers,
  );

  const locName = `${s}-loc`;
  const loc = await post<Entity>(
    page,
    "/api/v1/locations",
    {
      name: locName,
      scanCode: locName,
      locationTypeId,
      areaId: area.id,
    },
    headers,
  );

  return { locationId: loc.id, locationName: locName, locationTypeId };
}

/** Seeds a unit-load + stock unit at a specific location (not using the `areas` struct). */
export async function seedStockAtLocation(
  page: Page,
  headers: Headers,
  args: {
    product: Product;
    locationId: number;
    locationName: string;
    locationTypeId: number;
    unitLoadTypeId?: number;
    amount: number;
    prefix?: string;
  },
): Promise<StockSeed> {
  const s = uniq(args.prefix ?? "stk").toUpperCase();

  const unitLoadTypeId =
    args.unitLoadTypeId ??
    (
      await post<Entity>(
        page,
        "/api/v1/unit-load-types",
        {
          name: `${s}-pallet`,
          height: 150,
          width: 120,
          depth: 100,
          weight: 25,
          liftingCapacity: 1500,
        },
        headers,
      )
    ).id;

  const ul = await post<Entity>(
    page,
    "/api/v1/unit-loads",
    {
      clientId: 1,
      labelId: `${s}-UL`,
      unitLoadTypeId,
      storageLocationId: args.locationId,
      storageLocationName: args.locationName,
    },
    headers,
  );

  const stock = await post<Entity>(
    page,
    "/api/v1/stock-units",
    {
      itemDataId: args.product.id,
      itemDataNumber: args.product.number,
      amount: args.amount,
      unitLoadId: ul.id,
      state: INCOMING,
    },
    headers,
  );

  // Advance to ON_STOCK.
  await post(
    page,
    `/api/v1/stock-units/${stock.id}/change-state`,
    { state: ON_STOCK },
    headers,
  );

  return { stockUnitId: stock.id, unitLoadId: ul.id };
}

export type CountSessionView = {
  id: number;
  sessionNumber: string;
  type: string;
  state: number;
  orders: CountOrderView[];
};

export type CountOrderView = {
  id: number;
  orderNumber: string;
  locationId: number;
  locationName: string;
  state: number;
  lines: CountLineView[];
};

export type CountLineView = {
  id: number;
  stockUnitId: number;
  itemDataNumber: string;
  lotNumber: string | null;
  plannedAmount: number | string;
  countedAmount: number | string | null;
  state: number;
  // St4 UL identity — the backend CountLineView carries these; null on pre-migration lines.
  unitLoadId: number | null;
  unitLoadLabel: string | null;
};

export type CountEntryView = {
  id: number;
  orderNumber: string;
  locationName: string;
  lines: CountEntryLine[];
};

export type CountEntryLine = {
  lineId: number;
  itemDataNumber: string;
  lotNumber: string | null;
  serialNumber: string | null;
  // St4 — UL grouping key for the operator UI, and the "already resolved elsewhere" flag.
  unitLoadId: number | null;
  unitLoadLabel: string | null;
  counted: boolean;
};

/**
 * Starts a cycle-count session for the given location ids.
 * Returns the full CountSessionView (with orders and lines).
 * Field names match StartCountRequest / CountSessionView DTOs.
 *
 * POST /api/v1/count-sessions now returns the lightweight summary projection (no nested
 * orders -- defect-burndown row 8), so this helper follows up with a GET /count-sessions/{id}
 * to preserve its documented "full graph" return contract for every existing call site.
 */
export async function startCount(
  page: Page,
  headers: Headers,
  locationIds: number[],
  blindCount = true,
): Promise<CountSessionView> {
  const started = await post<{ id: number }>(
    page,
    "/api/v1/count-sessions",
    { locationIds, blindCount },
    headers,
  );
  return get<CountSessionView>(
    page,
    `/api/v1/count-sessions/${started.id}`,
    headers,
  );
}

// ── Receiving helpers (ASN / goods receipt, inbound-completion sprint) ────────

export type AsnLine = {
  id: number;
  lineNumber: number;
  itemDataId: number;
  itemDataNumber: string;
  expectedAmount: number | string;
  receivedAmount: number | string;
  remainingAmount: number | string;
  progressPercent: number;
  state: number;
  stateName: string;
  lotNumber: string | null;
};

export type Asn = {
  id: number;
  asnNumber: string;
  state: number;
  stateName: string;
  progressPercent: number;
  lines: AsnLine[];
};

/**
 * Seeds an ASN with one line per entry in `lines` (default: a single line of
 * `amount` against a freshly seeded product), and releases it (CREATED ->
 * RELEASED) unless `release: false` is passed. Field names mirror
 * CreateAsnRequest/CreateAsnLineRequest; the response (create or release,
 * whichever ran last) mirrors AsnResponse.
 */
export async function seedAsn(
  page: Page,
  headers: Headers,
  args: {
    lines?: Array<{
      product: Product;
      expectedAmount: number;
      lotNumber?: string;
      /** Pre-distributed cross-docking target (Advanced Fulfillment, Task 8) -- mirrors
       *  `CreateAsnLineRequest.crossDockDeliveryOrderId`: the delivery-order id this line
       *  should be matched against at receiving time, not a line id. */
      crossDockDeliveryOrderId?: number;
    }>;
    product?: Product;
    amount?: number;
    prefix?: string;
    carrierName?: string;
    release?: boolean;
  } = {},
): Promise<Asn> {
  const prefix = args.prefix ?? "asn";
  const lineSeeds: NonNullable<typeof args.lines> = args.lines ?? [
    {
      product: args.product ?? (await seedProduct(page, headers, prefix)),
      expectedAmount: args.amount ?? 100,
    },
  ];

  const asn = await post<Asn>(
    page,
    "/api/v1/asns",
    {
      asnNumber: uniq(prefix),
      carrierName: args.carrierName ?? "E2E Carrier",
      lines: lineSeeds.map((l) => ({
        itemDataId: l.product.id,
        expectedAmount: l.expectedAmount,
        lotNumber: l.lotNumber,
        crossDockDeliveryOrderId: l.crossDockDeliveryOrderId,
      })),
    },
    headers,
  );

  if (args.release ?? true) {
    return post<Asn>(page, `/api/v1/asns/${asn.id}/release`, {}, headers);
  }
  return asn;
}

export type GoodsReceiptAsnRef = { id: number; asnNumber: string };

export type GoodsReceipt = {
  id: number;
  receiptNumber: string;
  asns: GoodsReceiptAsnRef[];
  state: number;
  stateName: string;
  dockLocationId: number | null;
  dockLocationName: string | null;
  operatorId: string | null;
  pausedAt: string | null;
  lines: unknown[];
};

/**
 * Opens a goods receipt, optionally bound to one or more RELEASED/STARTED
 * ASNs (V424 many-to-many: `asnIds`) and/or a dock location (makes it visible
 * to the floor work inbox, see ReceivingWorkProvider). Omitting
 * `receiptNumber` lets the server generate one (GR-prefixed, NOT e2e-swept by
 * number alone) -- pass `prefix` to get a nanoTime-suffixed `e2e-` number the
 * db-cleanup sweep matches directly; an ASN-linked receipt is swept via the
 * goods_receipt_asns join table regardless of its own number.
 */
export async function seedGoodsReceipt(
  page: Page,
  headers: Headers,
  args: {
    asnIds?: number[];
    dockLocationId?: number;
    dockLocationName?: string;
    carrierName?: string;
    receiptNumber?: string;
    prefix?: string;
  } = {},
): Promise<GoodsReceipt> {
  return post<GoodsReceipt>(
    page,
    "/api/v1/goods-receipts",
    {
      receiptNumber: args.receiptNumber ?? (args.prefix ? uniq(args.prefix) : undefined),
      asnIds: args.asnIds ?? [],
      carrierName: args.carrierName ?? "E2E Carrier",
      dockLocationId: args.dockLocationId,
      dockLocationName: args.dockLocationName,
    },
    headers,
  );
}

export type ReceivedLine = {
  receipt: GoodsReceipt;
  lineId: number;
  stockUnitId: number;
  unitLoadId: number;
  unitLoadLabel: string;
};

/**
 * Receives one line on an open goods receipt. Either `asnLineId` (against a
 * bound ASN line) or `itemDataId` (blind) must be given -- mirrors
 * ReceiveLineRequest's own either/or contract. Field names match
 * ReceiveLineRequest; the response mirrors ReceiveLineResponse.
 */
export async function receiveLine(
  page: Page,
  headers: Headers,
  receiptId: number,
  args: {
    asnLineId?: number;
    itemDataId?: number;
    amount: number;
    locationId: number;
    locationName: string;
    unitLoadLabel?: string;
    lockType?: number;
    allowOverReceipt?: boolean;
  },
): Promise<ReceivedLine> {
  return post<ReceivedLine>(
    page,
    `/api/v1/goods-receipts/${receiptId}/lines`,
    {
      asnLineId: args.asnLineId,
      itemDataId: args.itemDataId,
      amount: args.amount,
      locationId: args.locationId,
      locationName: args.locationName,
      unitLoadLabel: args.unitLoadLabel,
      lockType: args.lockType,
      allowOverReceipt: args.allowOverReceipt ?? false,
    },
    headers,
  );
}

// ── Transport-chain helpers (putaway-transport sprint, Task 7) ─────────────

export type TransferStagingArea = {
  clusterId: number;
  storageAreaId: number;
  locationId: number;
  locationName: string;
};

/**
 * Seeds a transfer-staging waypoint: a LocationCluster + a StorageArea(transferStaging=true)
 * over that cluster + one REAL location that's a member of the cluster
 * (`LocationLockPort.isTransferStaging` is a real repository query, not a stub -- mirrors the
 * backend's `TransferChainFlowTest` fixture shape). The location's own Area is deliberately
 * given NO usages (empty list): a caller driving the REAL receive -> auto-putaway path (not a
 * direct-repository seed like the backend QuarkusTest) needs the staging location to stay OUT
 * of the putaway finder's candidate pool, or a predecessor's own suggestion could collapse onto
 * it (`suggestedLocationId === stagingLocationId`) and skip the chain entirely --
 * `ChainContinuationService.maybeChain`'s `finalTarget == destId` check returns early in that
 * case, same as landing on the real final target.
 */
export async function seedTransferStagingArea(
  page: Page,
  headers: Headers,
  args: { locationTypeId: number; prefix?: string },
): Promise<TransferStagingArea> {
  const s = uniq(args.prefix ?? "stage");

  const cluster = await post<Entity>(page, "/api/v1/location-clusters", { name: `${s}-cluster` }, headers);
  const storageArea = await post<Entity>(
    page,
    "/api/v1/storage-areas",
    { name: `${s}-storagearea`, clusterIds: [cluster.id], transferStaging: true },
    headers,
  );
  const area = await post<Entity>(page, "/api/v1/areas", { name: `${s}-area`, usages: [] }, headers);
  const locName = `${s}-loc`;
  const loc = await post<Entity>(
    page,
    "/api/v1/locations",
    {
      name: locName,
      scanCode: locName,
      locationTypeId: args.locationTypeId,
      areaId: area.id,
      locationClusterId: cluster.id,
    },
    headers,
  );

  return {
    clusterId: cluster.id,
    storageAreaId: storageArea.id,
    locationId: loc.id,
    locationName: locName,
  };
}
