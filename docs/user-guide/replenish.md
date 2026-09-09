# Replenish

Replenishment keeps the places you pick from stocked, by moving goods there from bulk storage
before a picker finds an empty shelf.

A **fix assignment** is the setup that makes this possible: it says *this product lives at
this location*, and gives that pairing a **minimum** and a **target** quantity. Once a
location has one, Karyo can tell when it is running down.

## Where to look

Open **Fulfillment > Tasks** and filter to **Replenish**. (The older `/replenishment` address
redirects here.) The **Replenishment needs** panel shows what is short:

| Column | Meaning |
| --- | --- |
| **Location** | The pick face that needs stock. |
| **SKU** | The product it holds. |
| **Current** | What is there now. |
| **Min** | Below this, it is urgent. |
| **Target** | What a top-up should bring it back to. |
| **Status** | **Below min** (red) or **Low** (outline). |
| **Task** | **Open task** when replenishment work already exists for it. |

**Below min** and **Low** are different. **Low** means it has fallen under target and should
be topped up soon. **Below min** means it is under the minimum and a picker may be about to
find it empty. Work the red ones first.

The **Open task** tag matters just as much: it means somebody has already been sent to do it.
Do not create a second one.

If nothing is short, the panel says *"No open replenishment needs."*

## Finding what needs replenishing

Press **Scan now** (you need write permission on tasks). Karyo checks every fix assignment,
works out what is short, and creates replenishment tasks for the shortfalls it can serve.

The scan reports back in **Last scan results**, with two lists:

- **Generated tasks**: replenishment work now waiting for an operator.
- **Shortfalls**: places that are short where Karyo could **not** create a task, usually
  because there is no stock in bulk to move.

That second list is the one to read carefully. A shortfall with no task is a real problem the
system cannot solve for you: it needs receiving, not moving.

The scan results are **held in your browser session only and are not stored**. If you refresh
the page they are gone. The needs table above them is live and always current, so nothing is
lost; you simply cannot come back later to the same scan report.

Two kinds of row can appear:

- **Fix face**, a specific location running down.
- **Area**, where a whole area is short of a product rather than one named slot.

The needs table lists fix-face rows only. Area shortfalls appear as a result of running a
scan, and not otherwise.

## Automatic scanning

Karyo can run the same scan on a schedule instead of waiting for someone to press the button.
**It is off by default**, per goods owner, and an administrator has to turn it on for your
site. Until then, replenishment is on-demand: someone has to press **Scan now**.

If you are relying on replenishment happening by itself, confirm with your administrator that
it has actually been enabled. See [replenishment](../functional/replenishment.md) for the
scan rules and [Administer Karyo](administer-karyo.md) for where the setting lives.

## Doing the replenishment

A replenishment reaches the floor as ordinary work on the WORK tab. It behaves exactly like a
[putaway](receive-and-put-away.md): scan the source unit load, walk to the destination, scan
the location, complete.

The record updates when you complete it, not when you set off.

## One control that does not work

The **Replenish** button on an item's detail page under **Warehouse > Items** is not
connected. Pressing it says so and does nothing. Use **Scan now** on the Tasks board instead,
which is the working route and the one described above.

## When replenishment is not the answer

- **Nothing in bulk to move.** The scan reports a shortfall with no task. Receive stock.
- **The pick face has no fix assignment.** Karyo cannot know a location is short if nobody
  told it what should be there or how much. See [Administer Karyo](administer-karyo.md).
- **The stock is held.** Held stock will not be moved into a pick face. Resolve the hold
  first; see [find stock](find-stock.md).

## Next

- [Pick an order](pick-an-order.md)
- [Count stock](count-stock.md) if the pick face says one thing and the shelf another
