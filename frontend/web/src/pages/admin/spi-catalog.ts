/**
 * Static SPI/extension-seam catalog (Workstream A, demo-hardening — Task 6).
 *
 * Backs the Admin -> Extensions (SPI) page (route: `/admin/strategies`,
 * unchanged). That page used to be a mock "AI governance registry" over
 * fake providers/toggles; it has been replaced with this curated, read-only
 * catalog of the REAL extension seams shipped in the backend + their real
 * built-in implementations. Every entry below was verified against the
 * source (see the task report for the exact grep evidence) — nothing here
 * is fabricated, and nothing on the page mutates state.
 *
 * This is the on-ramp to Workstream D, which will make these seams
 * live-swappable (discover installed beans at runtime instead of a static
 * list). For now it is intentionally static.
 */

export interface SpiSeam {
  id: string;
  name: string;
  /** Fully-qualified Kotlin interface, or a short description when there is no code-level SPI. */
  spi: string;
  /** Owning module (as named in services/, e.g. "karyo-monitors"). */
  module: string;
  /** Shipped implementation(s) — a class name, a stable string key, or both. */
  builtIns: string[];
  /** Whether third parties can add implementations (drop-in JAR, or JSONB config). */
  extensible: boolean;
  note: string;
  /** Present when the seam is configured through a live screen rather than a JAR. */
  configuredAt?: string;
}

export const SPI_CATALOG: readonly SpiSeam[] = [
  {
    id: 'detector',
    name: 'Detector',
    spi: 'com.karyo.monitors.spi.Detector',
    module: 'karyo-monitors',
    builtIns: [
      'expiry-risk',
      'stuck-order',
      'bin-below-reorder',
      'cycle-count-variance',
      'shrinkage',
      'putaway-backlog',
    ],
    extensible: true,
    note: 'Deterministic rule detectors ship in v1.7a; statistical/ML detectors can implement the same interface later with no change to the alert pipeline. Each built-in is an @ApplicationScoped CDI bean in karyo-monitors-core, indexed by monitorKey.',
  },
  {
    id: 'forecast-model',
    name: 'Forecast Model',
    spi: 'com.karyo.forecasting.spi.ForecastModel',
    module: 'karyo-forecasting',
    builtIns: ['ewma'],
    extensible: true,
    note: 'A deterministic EWMA model ships in v1.7b (key "ewma"); statistical/ML models (Holt-Winters, Croston) can implement the same interface later.',
  },
  {
    id: 'slotting-strategy',
    name: 'Slotting Strategy',
    spi: 'com.karyo.slotting.spi.SlottingStrategy',
    module: 'karyo-slotting',
    builtIns: ['abc-proximity'],
    extensible: true,
    note: 'ABC-velocity vs. slot-proximity ships in v1.7c (key "abc-proximity"); affinity/ML/alternative-metric strategies can implement the same interface later.',
  },
  {
    id: 'reorder-policy-simulator',
    name: 'Reorder Policy Simulator',
    spi: 'com.karyo.simulation.spi.ReorderPolicySimulator',
    module: 'karyo-simulation',
    builtIns: ['lost-sales'],
    extensible: true,
    note: 'A deterministic continuous-review (s, Q) lost-sales replay ships in v1.7d (key "lost-sales"); backorder or stochastic/Monte-Carlo models can implement the same interface later.',
  },
  {
    id: 'stock-selection-filter',
    name: 'Stock Selection Filter',
    spi: 'com.karyo.inventory.api.spi.StockSelectionFilter',
    module: 'karyo-inventory',
    builtIns: ['HazmatStockFilter (karyo-inventory-ext-example)'],
    extensible: true,
    note: 'Filters or reorders stock-selection candidates ahead of the core 13-pass FIFO algorithm. No filter ships active in core — HazmatStockFilter in karyo-inventory-ext-example is a reference @Alternative @Priority extension JAR demonstrating the drop-in-a-JAR pattern; it is not wired into the running app by default.',
  },
  {
    id: 'carrier-adapter',
    name: 'Carrier Adapter',
    spi: 'com.karyo.fulfillment.spi.CarrierAdapter',
    module: 'karyo-fulfillment',
    builtIns: ['ManualCarrierAdapter'],
    extensible: true,
    note: 'ADR-036 strategy SPI. v1.3 ships only ManualCarrierAdapter — the catch-all (handles every carrier) that uses the operator-supplied tracking number or generates one. Real carrier adapters (FedEx/UPS/DHL) register as beans, claim a carrier via handles(), and run at a lower priority so they win for their carrier while Manual stays the fallback.',
  },
  {
    id: 'packout-strategy',
    name: 'Packout Strategy',
    spi: 'com.karyo.fulfillment.spi.PackoutStrategy',
    module: 'karyo-fulfillment',
    builtIns: ['ONE_TO_ONE (OneToOnePackout)'],
    extensible: true,
    note: 'ADR-036 strategy SPI. v1.3 ships only OneToOnePackout (one confirmed pick container -> one shipping unit + weight). CartonizationPackout (re-pack eaches into N boxes) and ConsolidationPackout are future beans; custom strategies are priority-ordered ahead of the built-in.',
  },
  {
    id: 'product-lookup',
    name: 'Product Lookup',
    spi: 'com.karyo.product.spi.ProductLookup',
    module: 'karyo-product',
    builtIns: ['DefaultProductLookup'],
    extensible: false,
    note: 'In-process cross-module lookup contract, not a customer extension point — warehouse-layout injects this instead of making a cross-service REST call. Implemented once, by product-core.',
  },
  {
    id: 'stock-unit-lookup',
    name: 'Stock Unit Lookup',
    spi: 'com.karyo.inventory.api.spi.StockUnitLookup',
    module: 'karyo-inventory',
    builtIns: ['DefaultStockUnitLookup'],
    extensible: false,
    note: 'In-process cross-module lookup contract, not a customer extension point — warehouse-layout injects this instead of making a cross-service REST call. Implemented once, by inventory-core.',
  },
  {
    id: 'adr036-strategies',
    name: 'Order & Storage Strategies (ADR-036)',
    spi: 'JSONB-configured strategy knobs — not a compiled SPI',
    module: 'karyo-orders / karyo-layout',
    builtIns: ['DEFAULT order strategy', 'DEFAULT storage strategy'],
    extensible: true,
    note: 'A 3-tier config seam (env defaults -> DB strategy row -> JSONB extensionProperties) resolved at runtime by OrderStrategyResolver for orders (layout resolves inline) — no JAR required. Manage the live strategies here.',
    configuredAt: '/strategies',
  },
];
