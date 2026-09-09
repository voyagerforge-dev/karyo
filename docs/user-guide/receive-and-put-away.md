# Receive and put away

Goods arriving is where warehouse records start. Get this right and everything downstream
follows; get it wrong and no amount of picking discipline recovers it.

Karyo splits arriving goods into two records:

- An **ASN** (advance shipping notice) is what somebody **said** is coming.
- A **goods receipt** is what **actually** arrived and what you did about it.

A receipt can be linked to one ASN, to several, or to none at all. A receipt with no ASN is a
**blind receipt**: goods turned up unannounced and you are recording what is in front of you.

## Before the lorry: the ASN

Open **Inbound > ASNs** on the desktop console. The list filters to **All**, **Created**,
**Receiving** and **Done**, and an ASN moves through those in order as it is worked.

An ASN carries the expected lines: which product, how much, and optionally a lot. You do not
have to have one. Karyo receives perfectly well without it; the ASN only buys you the ability
to compare what arrived against what was promised.

## Booking in the delivery

### On the desktop console

Open **Inbound > Receiving**. The list filters to **All**, **Open**, **Paused** and **Done**.
Each row shows the receipt number, its status, its priority when one is set, and where its
expectation came from: an ASN number, `N ASNs` when several are linked, or **Blind**. A
returned-goods receipt carries a **RETOUR** tag.

Press **New receipt** (you need write permission on orders) and then open the receipt to get
the **receiving workbench**, the full-page screen where the actual booking-in happens.

The workbench header shows the receipt number, its state, links to each linked ASN, and
**Claimed by** when someone has taken it. Below that:

- **The Expected pane**, on the left, lists every still-outstanding line across every linked
  ASN, showing **Product**, which **ASN** it came from, and the **Remaining** quantity. Press
  **Receive** on a line to load it into the form. On a blind receipt there is no expectation
  to show, so you pick the product yourself.
- **The receive form**, on the right, is what you fill in for each line:

| Field | What to put in it |
| --- | --- |
| **Product** | Prefilled when you came from an expected line. |
| **Amount** | What you actually counted. Not what the paperwork claims. |
| **Unit load label** | The pallet or carton label this stock goes onto. |
| **Packaging unit** | The unit the amount is expressed in. |
| **Lot** | Required for lot-tracked products. |
| **Best before** | For dated stock. |
| **Location** | Where you are physically putting it right now, usually a dock or goods-in area. |
| **Lock** | Set this to hold the stock instead of releasing it. See below. |
| **Note** | Anything the next person needs to know. |

Receive each line, then press **Finish receipt**. You are asked to confirm, because finishing
closes the receipt to further lines.

If the receipt has been **paused**, a banner says *"Paused since ... resume to continue
receiving"* with a **Resume** button. You cannot receive into a paused receipt, and Finish is
disabled until it is resumed.

### On the floor app

Choose **3 Receive** from the MENU tab. You get the list of open ASNs; select one and work
its lines.

Each line asks you to scan or confirm, then enter the quantity on a large keypad, then
**CONFIRM RECEIVE**. There is an optional unit-load label scan. When every line is done,
**REVIEW** then **FINISH**.

Two refusals worth recognising:

- ***"Received more than the ASN allows for this line"*** - you are trying to book in more
  than was announced. Either you miscounted, or the delivery is genuinely over. An over-
  delivery is a decision, not a keying error: take it to a supervisor.
- ***"This receipt is closed"*** - somebody finished the receipt while you were working it.

Receiving needs a connection. Offline, the transaction is unavailable.

## Holding stock instead of releasing it

Stock you are not happy with should not become available to pick. Set a **Lock** on the line
as you receive it. Locked stock stays in the record, keeps its location and its quantity, and
shows as **Hold** everywhere it appears, but nothing will allocate it.

This is the correct response to damage, a quality question, a wrong lot, or a missing
certificate. Do not delete stock to keep it off the floor. Hold it, so it stays counted and
somebody can decide about it later.

See [find stock](find-stock.md) for what a hold looks like from the other side.

## Putaway happens on its own

You do not create putaway work. When a receipt line is received, Karyo reacts:

1. It skips any line you put on hold. Held stock is not ready to put away.
2. For every other line, it finds the unit load and asks for a destination location.
3. It creates a **Putaway** task and releases it into the work queue.

So by the time you have finished the receipt, the putaway work already exists and an operator
pressing **GET NEXT TASK** can pick it up.

Two behaviours to know:

- **If no suitable destination can be found, the task is still created**, without a suggested
  location and carrying a note that says why, but it is left unreleased. The work is not
  silently dropped, and it is not offered as floor work either: an unreleased task never
  reaches **GET NEXT TASK** and does not appear on the Tasks board under any filter. It is
  visible through `GET /api/v1/transport-orders`. To move that pallet, create a **New move**
  on **Fulfillment > Tasks** and name the destination yourself.
- **One task per receipt line.** Receiving the same line again does not mint a duplicate.

Where the suggested location comes from is a configured decision. See
[location finding](../functional/location-finder.md) for the rule your installation follows.

## Doing the putaway

A putaway arrives on the floor app as ordinary work.

1. **GET** shows the unit load label and the location it is at now. Scan the unit load to
   confirm you have the right one.
2. **PUT AT** shows the destination. Walk there and scan the location.
3. Press **COMPLETE**.

Karyo moves the stock in the record only when you complete the task, so the system's picture
matches the shelf rather than an intention.

If the destination is wrong for the goods in front of you, **Release task** and tell a
supervisor rather than putting it somewhere else. The record has to describe reality.

## Cross-docking

If goods should go straight to outbound staging instead of into storage, that is
**cross-docking**, and it is a commercial engine. Without it, every received line follows
ordinary putaway, which is the correct and complete behaviour of the free application.
See [PAID-MODULES.md](../../PAID-MODULES.md).

## Next

- [Find stock](find-stock.md) to see what you just received
- [Count stock](count-stock.md) if the shelf and the record disagree
