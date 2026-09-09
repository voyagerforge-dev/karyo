# Pick an order

An order becomes picked work in two deliberate steps. Understanding the difference between
them is the single most useful thing on this page.

1. **Release** decides *whether we can do it*: Karyo reserves real stock against each line.
2. **Release to picking** decides *that we are doing it now*: Karyo turns those reservations
   into pick work for the floor.

Between the two, an order is committed but nobody is walking yet. That gap is on purpose.

## The Orders list

Open **Inbound > Orders**. Filters are **All**, **Picking**, **Exception** and **Ready**.
Each row shows:

- the order number, with a **PRIORITY** tag on high-priority orders;
- a status pill;
- a **progress bar** showing lines completely picked out of total lines;
- the line count and the ship-by date.

The progress bar counts a line as done only when its picked (or substituted) quantity meets
what was ordered. A part-picked line does not count, which is what you want when you are
looking for what still needs work.

**Create Order** opens the order form. Search matches the order number and the customer.

## Step 1: Release, which is where allocation happens

Open an order in state **Created** and press **Release**. Karyo walks each line and reserves
stock for it, following the order's configured picking strategy.

What happens next depends entirely on whether it found enough:

- **Every line fully reserved** puts the order in **Processable**. It is ready to be picked.
- **Any line short** puts the order in **Pending**. Karyo has reserved what it could and is
  telling you the rest is not there.

**Pending is not a failure and not a queue.** It is Karyo saying the stock does not exist yet.
Your options are to receive more, [replenish](replenish.md) into the pick face, release the
reservation and cancel, or let a later retry pick it up once stock arrives.

A red Copilot strip above the line items reports the shortfall, offering **Substitute** and
**Backorder**. Neither is connected, so treat it as a description and use the options above.
Do not read the strip as a verdict on the order: it appears for **any line whose reserved
amount is below the amount ordered**, with no check on the order's state. On an order you have
not released yet nothing is reserved, so every line is short and the strip is already there.
It clears line by line as reservations cover the amounts.

Reserved stock immediately reads as **Allocated** on [Inventory](find-stock.md). It is still
physically on the shelf, but it is spoken for and Karyo will not offer it to another order.

## Step 2: Release to picking

With the order **Processable**, the button becomes **Release to picking**. Pressing it turns
the reservations into one or more **pick orders**, which is the work an operator actually
receives.

One delivery order can become several pick orders. That is normal: Karyo splits by the target
unit load and by what the picking strategy says.

Three refusals you may meet, each meaning something specific:

| Message | What it means |
| --- | --- |
| *"no reserved stock to pick"* | You skipped step 1, or the reservations were released. Release the order first. |
| *"no PACK_STAGING location configured"* | Your warehouse has no pack-staging location. This is a setup gap, not an order problem. See [Administer Karyo](administer-karyo.md). |
| *"already released to picking"* | Somebody beat you to it. |

### Controls on this screen that are not connected

When an order is in neither of the two states above, the primary button reads **Allocate**.
**It is not connected**: pressing it shows a short "not wired yet" message and does nothing.
The two working actions are **Release** and **Release to picking**, described above.

The same applies to **Print docs**, **Hold** and the **More actions** (`...`) button beside
them, and to **Substitute** and **Backorder** on the red Copilot strip that appears whenever a
line is short. That strip's **Dismiss** button behaves differently and is worth knowing about:
it reports *"Exception dismissed"* and stores nothing, so the strip returns the moment the
screen refreshes. The shortfall goes away when the stock arrives, not when you dismiss it.

The delivery note button that appears once an order reaches **Picked** is real, and produces
a document.

## What else the order screen tells you

- **Line items**: what was ordered, and what has been picked against it.
- **Ship to**: the delivery address, or *"Address not on file"* when there is none.
- **Assignment**: who has claimed the order, with a claim and release control.
- **Activity**: the audit trail.
- **Streaming**: only when the order streaming engine has stamped the order. Free
  installations will not see it. See [PAID-MODULES.md](../../PAID-MODULES.md).

**Cancelling.** An order can be cancelled while it is below **Picked**. Once goods have been
picked, it must run forward through packing rather than be cancelled, because the stock has
already left its home.

The pipeline timestamps on the order show **Placed** and **Shipped** as real times, and leave
the intermediate stages as a dash. There is no per-stage history behind them, and Karyo shows
a dash rather than inventing a time.

## Step 3: Picking on the floor

The pick reaches the operator like any other work: **GET NEXT TASK** on the WORK tab.

Each line runs the same three steps.

1. **GO TO** shows the location in large type. Scan the location to confirm you are there.
2. **ITEM** shows the product. Scan the item to confirm you have the right one.
3. **QTY** gives you a keypad. Enter what you actually took, and press **CONFIRM**.

A step counter shows which line you are on and how many there are. When the last one is
confirmed, the screen reads **Pick complete**.

Scanning the wrong location or the wrong item does not advance the screen; it tells you what
it expected. That is the whole point of confirming by scan: the two chances to pick the wrong
thing are stopped before the quantity is entered.

**Enter what you took, not what was asked for.** If only seven are there and the pick says
ten, confirm seven. Karyo can reconcile a short pick. It cannot reconcile a number you typed
to make the screen go away.

If you cannot do the pick at all, **Release task** and it returns to the pool.

## Supervising picks from the desktop

**Fulfillment > Tasks** is the unified work board: every kind of work in one list, filtered
by **All**, by work type (**Pick**, **Putaway**, **Move**, **Replenish**, **Count**,
**Receive**, **Transfer**, **Cross-dock**), or by **Paused**.

Each row shows the work type, its priority as `P` and a number, and **Claimed** with the
operator's name when someone holds it. Selecting one opens its detail, where you can **Claim**
unclaimed work or **Release** work you hold.

Paused work is a separate lane. Paused items do not appear in the ordinary list at all, which
is why the **Paused** chip exists: it is the only way to see them.

The pick detail pane shows each pick line with its item, SKU, source unit load, location, lot,
planned quantity and picked quantity, so you can see exactly where a part-picked order stands.

The older `/pick-orders` and `/replenishment` addresses now redirect into this board filtered
to the right type. There is nothing separate to look at.

## Wave and streamed picking

Batch-picking many orders together (**waves**) and releasing orders automatically in small
batches (**streaming**) are commercial engines. Their screens show a locked panel, and the
floor **Sort** and **Pack-out** transactions need the wave engine.

Discrete picking, one order at a time as described above, is the free application's picking
and is complete on its own. See [PAID-MODULES.md](../../PAID-MODULES.md).

## Next

- [Pack and ship](pack-and-ship.md)
- [Replenish](replenish.md) when picks keep coming up short
