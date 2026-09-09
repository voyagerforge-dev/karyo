# Using Karyo

This guide is for the people who run the warehouse: the clerk booking in a delivery, the
operator on a scanner, the supervisor deciding what gets picked next, the administrator
setting up goods owners and users.

It is organised by **what you are trying to do**, not by which part of the software does it.
You do not need to know how Karyo is built to use it. If you are installing Karyo rather than
using it, start with [DEPLOY](../../DEPLOY.md) and the
[implementer guide](../guides/implementer-guide.md) instead.

## Start here

- **[Your first sign-in](first-sign-in.md)** - the two interfaces, which one you belong on,
  what your role lets you see, and what to do when a screen is missing.

## Learn your interface

- **[The desktop console](desktop-console.md)** - the planner and administrator surface: the
  Operations Control dashboard, the sidebar, and how every list screen works the same way.
- **[The floor app](floor-app.md)** - the operator surface: the work inbox, the numbered
  transaction menu, scanning, and what happens when the device loses its connection.

## Do the work

| You want to | Read |
| --- | --- |
| Book in a delivery and put it away | [Receive and put away](receive-and-put-away.md) |
| Find stock and understand available, allocated and held | [Find stock](find-stock.md) |
| Get an order picked | [Pick an order](pick-an-order.md) |
| Pack a picked order and ship it | [Pack and ship](pack-and-ship.md) |
| Keep pick faces stocked | [Replenish](replenish.md) |
| Count stock and correct the record | [Count stock](count-stock.md) |
| Add a goods owner, a user, or a location | [Administer Karyo](administer-karyo.md) |

## What this guide does not cover

**Commercial engines.** Nine optional engines are not part of the free application, so their
screens show a locked panel instead of data. This guide says so wherever you would meet one,
and never describes an engine you do not have. [PAID-MODULES.md](../../PAID-MODULES.md) owns
what each engine does, what it does not do, and how to ask about it.

**Exact algorithms.** Where Karyo chooses stock, chooses a location, or breaks a tie, this
guide tells you what to expect and points at the functional specification that fixes the
rule: [stock selection](../functional/stock-selection.md),
[location finding](../functional/location-finder.md), [picking](../functional/picking.md),
[replenishment](../functional/replenishment.md) and
[inventory operations](../functional/inventory-operations.md).

**Your warehouse's own rules.** Karyo is configured per installation. Location names, unit
load types, strategies, roles and which goods owners exist are all decisions someone made
for your site. Where a screen reflects a configured choice rather than a fixed behaviour,
this guide says so.

## A note on the screens described here

Every screen, field and button in this guide was checked against the source in this
repository. Where a control exists but is not yet connected to anything, that is stated
plainly rather than left for you to discover. No screenshots are included: capturing them
honestly needs a running warehouse with real data on it, and an invented screenshot would be
worse than none.
