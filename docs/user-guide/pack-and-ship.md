# Pack and ship

Picked stock is standing at pack staging. Two things still have to happen: it has to be
packed into shipping units, and those units have to leave the building with the carrier and
paperwork recorded against them.

## Packing

### On the desktop console

Open **Fulfillment > Packing**. The list filters to **Ready to pack** and **In progress**,
and each row shows what has been **Picked**.

Select what you are packing and fill in the pack form:

| Field | Notes |
| --- | --- |
| **Carton type** | The kind of shipping unit you are building. |
| **Weight (kg)** | The real weight of the packed unit. |

The detail pane lists what is going in: item, lot, amount and type.

### On the floor app

Choose **5 Pack** from the MENU tab.

1. **Scan the picked unit load.** Karyo resolves which pick order it belongs to and shows the
   pick order number, so you can confirm you have the right thing.
2. **Enter the weight in kilograms.** A weight greater than zero is required; leaving it blank
   or entering zero gives you *"Enter a weight"*.
3. **Choose the type**, **CARTON** or **PALLET**.
4. Press **PACK**.

The screen confirms *"Packed <label> as CARTON"* and offers **Pack another unit**.

One shipping unit per pass. Manifesting and dispatch are separate transactions later in the
lifecycle, so packing on the floor stays a single decision made at the bench.

Weigh the unit; do not estimate it. The weight is what goes on the carrier paperwork.

## Shipping

Open **Fulfillment > Shipments**. The filters follow the lifecycle exactly: **All**,
**Packing**, **Packed**, **Shipping**, **Shipped**.

A shipment leaves in **two steps**, and they are separate because they answer different
questions.

### Step 1: Manifest

**Manifest** is available on a shipment that is **Packed**. You supply the carrier and the
service, and optionally a tracking number.

Karyo resolves a carrier adapter, stamps the carrier and tracking onto the shipment and every
shipping unit in it, and moves the shipment to **Shipping**.

The free application ships a **manual** carrier adapter. It records what you tell it; it does
not call a carrier's system, book a collection, or fetch a rate. A live carrier integration is
an extension written against the published interface, and is not something that arrives
working. See [extensibility architecture](../architecture/extensibility-architecture.md).

**Manifest means the shipment is committed to a carrier**, not that it has left.

### Step 2: Dispatch

**Dispatch** is the moment the goods physically leave. Karyo resolves the ship-staging dock,
flips each shipping unit to **Shipped** and moves it to the dock, marks the shipment
**Shipped**, and drives the underlying order through Shipped to Finished.

Do not dispatch early. Dispatch is what tells the rest of the record that this stock is gone.

### Other actions on a shipment

- **Claim** and release, so two people do not work the same shipment. A claimed shipment shows
  **Claimed by** and its holder.
- **Pause** and **Resume**, for a shipment that has to wait.
- **Add unit** by unit load, and **Remove** a unit that should not be on this shipment. Both
  ask you to confirm, offering **Keep shipment** or **Keep unit** as the safe answer.
- **Cancel shipment**, which also confirms first.

### Documents

Once a shipment reaches the right state you can produce and archive:

- a **bill of lading**;
- a **packing slip**;
- a **packet list** for the shipment;
- a **contents** list;
- a **carrier label** per shipping unit, as ZPL for a label printer.

Each has an **Archive** action alongside it, which files the generated document in the
document archive under **Admin > Documents** so it can be produced again later without
regenerating it.

Ordinary document generation is part of the free application. Per-goods-owner **template
overrides** are a commercial engine; without it every goods owner gets the standard documents.
See [PAID-MODULES.md](../../PAID-MODULES.md).

## Reprinting a label on the floor

**6 Reprint** on the floor app: scan a unit load, check the label, type and location shown
back to you, and press **PRINT**. If no printer is configured for your site the screen says
so rather than silently doing nothing.

## Cross-order pack-out

Packing several orders out of one sorted batch is part of the wave fulfillment commercial
engine, and drives the floor **7 Sort** and **8 Pack-out** transactions. Without the engine,
one-to-one pack-out as described above is the complete free behaviour.
See [PAID-MODULES.md](../../PAID-MODULES.md).

## Next

- [Find stock](find-stock.md) to confirm the stock has left the record
- [Count stock](count-stock.md) if it has not
