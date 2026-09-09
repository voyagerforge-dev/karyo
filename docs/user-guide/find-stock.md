# Find stock

Most warehouse questions are really one question: *is this stock actually available?* Karyo
answers it on **Warehouse > Inventory** in the desktop console, and on **1 Inquiry** in the
floor app.

## Available, allocated, held: the distinction that matters

Karyo does not have one number for stock. It has three, and confusing them is the most common
mistake people make in a WMS.

| | Meaning |
| --- | --- |
| **On hand** | What is physically there. |
| **Allocated** | On hand, but already promised to an order. Still on the shelf; not yours to give away. |
| **Available** | On hand, not allocated, not held. This is the only number you can promise to a customer. |

On top of that, any stock can carry a **hold** (a lock), typically set during receiving for
damage or a quality question. Held stock is on hand, but nothing will allocate it.

The Inventory list reduces those to one status pill per row:

| Status | Colour | Means |
| --- | --- | --- |
| **Available** | green | there is available stock here, none of it promised to an order, and nothing held |
| **Allocated** | amber | some is promised to an order |
| **Hold** | red | some of the stock here is locked; the locked part will not allocate |
| **Out** | red | nothing usable left |

**Hold wins over the other pills.** One locked pallet makes the whole row read **Hold**, even
when the rest of the row is free. That pill is a flag on the row, not a verdict on the stock:
allocation looks at each pallet in turn, so the unlocked remainder of a **Hold** row is still
reserved and picked normally, and only the locked stock is passed over. Read it as *"something
here needs a decision"*, not *"nothing here can move"*. Open the row to see which unit loads
carry the lock.

**Any allocation at all makes the row read Allocated**, even when most of the group is still
free. A row holding 10 with 4 promised shows **Allocated**, not **Available**, and the
**Available** filter chip leaves it out. That chip means *"nothing here is spoken for"*, not
*"something here is pickable"*. To find every location with stock you could still promise,
read the **Available** column rather than filtering on the pill.

The practical rule: if you are answering "can we ship this today", read **Available**. If you
are answering "how much is in the building", read **On hand**. They are different questions
and they have different answers.

## The Inventory screen

Inventory does not list every pallet. It groups stock into **item at location** rows, because
"we have 40 of this in aisle B" is the useful unit, not "here are nine pallet labels".

**Two ways to look at the same stock**, toggled at the top right:

- **By item** answers *where is this product?*
- **By location** answers *what is in this place?*

**Search** matches item, SKU, location and lot together, so you can type a lot number or a
bin name without choosing a field first.

**Filter chips** narrow to **All**, **Available**, **Allocated** or **Held**.

**Five figures across the top**: On-hand units, Available, Allocated, Need reorder and
Stockouts. Four of them are labelled *"this page"*, and they mean it: they total the page of
records loaded, not the whole warehouse. Treat them as a read on what you are looking at, not
a warehouse-wide inventory report. **Need reorder** counts rows below their configured
reorder point.

**Export** downloads the stock records as a CSV file.

Each row shows the product name and status, the SKU and location, and either a count of
**LPNs** (the licence-plate labels, meaning the stock is tracked as identified unit loads) or
**LOOSE** (it is not). Selecting a row opens the detail pane with the full breakdown:
quantities, lots, expiry and the individual unit loads.

Where dates matter, expiry is coloured by urgency: red within 30 days, amber within 90,
otherwise green.

## Looking something up on the floor

**1 Inquiry** on the floor app is a read-only lookup and the fastest way to settle an argument
in an aisle. Scan anything.

- **Scan a unit load label** and you get its type, where it is, and every stock line on it
  with quantity and lot. An empty one says **Empty**.
- **Scan a location** and you get its name, whether it is **LOCKED** or **Unlocked**, and
  every unit load standing in it. An empty one says *"No unit loads on this location"*.
- **Scan something Karyo does not know** and it says so plainly rather than guessing.

Inquiry changes nothing. Use it freely.

## When the shelf and the record disagree

Do not adjust the number. **Count it.** A count produces a corrected record with an audit
trail behind it, and feeds the inventory-accuracy measure on the dashboard. See
[count stock](count-stock.md).

## What decides which stock gets picked

When an order needs stock, Karyo chooses among candidates. Whether it takes the oldest lot,
the nearest bin, or the one that empties a pallet, is a configured strategy rather than a
fixed rule. [Stock selection](../functional/stock-selection.md) is the specification, and
**Warehouse > Strategies** is where the choice is made for your site.

Two consequences worth knowing at the sharp end:

- **Held stock is never chosen.** That is the point of a hold.
- **Allocated stock is not chosen again.** Once promised, it stays promised until the order
  moves on or is cancelled.

## Next

- [Pick an order](pick-an-order.md)
- [Replenish](replenish.md) when a pick face is running down
