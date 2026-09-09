# The desktop console

The console is the surface for planners, supervisors and administrators. Open the site root
and sign in; every page below lives under it.

## Operations Control, the home page

The landing page is headed **Operations Control** with a live clock under it. It has:

- **A range selector** offering `7D`, `30D`, `90D` and `YTD`. It drives the numbers on this
  page, not the rest of the console.
- **A density toggle** offering **Comfortable** and **Command**. This only changes spacing.
  Pick whichever suits your monitor; the choice is remembered.
- **A KPI strip** of four measures.
- **Three cards**: Throughput, Zone occupancy and Exceptions.

### What the four KPIs actually mean

These are computed from your own data over the selected range, and each is worth
understanding before you act on it.

| KPI | What it measures |
| --- | --- |
| **Inventory accuracy** | Of the count lines closed in the range, the share that matched the record. Falls when counts keep finding discrepancies. |
| **Throughput** | Average units picked per day across the range. Units, not order or shipment counts. |
| **Order cycle time** | Average hours per order, over orders that completed in the range. Lower is better, and it is coloured that way. |
| **Utilization** | Occupied locations as a share of usable locations, **right now**. It is a snapshot, so it ignores the range selector and shows no trend. |

Each KPI except Utilization also shows a change against the equivalent preceding period. If
there is no prior data, no change is shown rather than a fabricated zero.

### The three cards

- **Throughput** charts one quantity: outbound units picked per day, one bar per day, with the
  **PEAK** day and the **AVG** of that same series above it. Shipments are deliberately not
  plotted, because a shipment count is not a unit quantity and mixing them would mislead. To
  compare picked against received, use the **Picked vs received** trend on **Insights >
  Reports**, which plots both.
- **Zone occupancy** is one small square per storage location, coloured **occupied**, **empty**
  or **locked**, with a legend below. Above it sits a single facility-wide percentage and a
  count of locked locations. It is not a per-zone breakdown: the squares are not grouped or
  labelled by zone, and a cell only names its zone when you hover it. For fullness zone by
  zone, use **Insights > Occupancy**.
- **Exceptions** lists fired warehouse alerts. **This card needs the Event monitors
  commercial engine.** Without it the card shows a locked panel reading *"Requires the
  Monitors add-on."* The rest of the dashboard works normally.

If your installation has demo data enabled, a sample-data card appears above the KPI strip.
On a production installation it is absent.

## The sidebar

The sidebar groups pages into **Overview**, **Inbound**, **Fulfillment**, **Warehouse** and
**Insights**. You only see entries your permissions allow, and a group with nothing visible
in it disappears entirely. At tablet width the sidebar collapses to icons on its own.

| Group | Pages |
| --- | --- |
| Overview | Control |
| Inbound | Orders, ASNs, Receiving |
| Fulfillment | Tasks, Packing, Shipments, Waves, Streaming |
| Warehouse | Locations, Items, Inventory, Cycle Count, Strategies, Users |
| Insights | Monitors, Reports, Occupancy, Forecasting, Slotting, Simulation |

Six of these need a commercial engine and otherwise show a locked panel: **Waves**,
**Streaming**, **Monitors**, **Forecasting**, **Slotting** and **Simulation**. **Reports** and
**Occupancy** are part of the free application. See
[PAID-MODULES.md](../../PAID-MODULES.md).

On the free **Reports** page, note that the **Export CSV** button is placed but not yet
connected: pressing it says so and produces no file. To get stock data out as CSV, use the
**Export** button on [Inventory](find-stock.md) instead, which is wired.

## Every list screen works the same way

Most console pages are a **master-detail** screen: a filtered list on the left, the selected
record on the right. Once you have used one, you have used all of them.

- **The search box** filters the list as you type, and searches the fields that matter for
  that screen (an order number and a customer, a stock item and its location, a receipt and
  its carrier).
- **Filter chips** above the list narrow it to a state, and on the workflow screens those are
  states a record can genuinely be in. Two screens have no chips at all - **Warehouse > Cycle
  Count** and **Admin > Clients** - so their search box is the only way to narrow them. A
  third, **Warehouse > Items**, has chips that need a commercial engine before they match
  anything. [Administer Karyo](administer-karyo.md) covers Clients and Items.
- **A coloured bar** on each row carries its status at a glance.
- **Selecting a row** fills the detail pane. Whether a row is picked for you depends on the
  screen: **Inventory**, **Items** and **Locations** open on the first row in the list. Most
  others - **Orders**, **ASNs**, **Receiving**, **Tasks**, **Packing**, **Shipments**,
  **Strategies**, **Cycle Count**, **Users** and **Admin > Clients** - start with an empty pane
  reading *"Select a ..."*. An empty pane on those screens means nothing has been clicked yet,
  not that the list failed.

One thing to know: several list screens **load one page of records and then filter and sort
within it**. Searching does not always reach across your entire history. Where a screen shows
totals over that page rather than the whole warehouse, it labels them *"this page"*.

## The admin section

Administrators get a separate `/admin` area with its own sidebar, grouped into **Configure**,
**Govern** and **System**. It is deliberately apart from the warehouse pages: this is where
you change how Karyo behaves, not what the warehouse is doing.

| Entry | What it is |
| --- | --- |
| **Extensions (SPI)** | The extension points this build exposes, and what is plugged into them. The number beside it is the real count of catalogued extension points. |
| **Integrations** | Webhook endpoints and their delivery. |
| **System properties** | Runtime settings, including per-goods-owner overrides. |
| **Unit load types** | The pallet, carton and tote types your warehouse uses. |
| **Clients** | Goods-owner administration. |
| **Users and roles** | Opens the Keycloak admin console in a new tab. Karyo has no user store of its own. |
| **Audit log** | Who did what. |
| **Documents** | The generated document archive. |
| **Document templates** | Per-goods-owner document overrides. **Commercial engine**; otherwise a locked panel. |
| **Health** | Whether the application and its dependencies are up. |

**Copilot**, **Tenants** and **Feature flags** appear greyed with a *"Coming soon"* tooltip.
They have no backend and are not clickable. They are shown rather than hidden so the shape of
the admin surface is honest about what is planned.

Note that **Clients** and **Tenants** are different things. A client is a goods owner inside
your single installation. A tenant would be a separate company on a shared installation,
which Karyo does not do.

## Next

- [Receive and put away](receive-and-put-away.md)
- [Find stock](find-stock.md)
- [Pick an order](pick-an-order.md)
- [Administer Karyo](administer-karyo.md)
