# Count stock

Counting is how the record gets corrected. Never adjust a number to make it match the shelf:
count the location, and let Karyo write the correction with an audit trail behind it.

Counts also feed the **Inventory accuracy** measure on the dashboard, so a warehouse that
counts honestly can tell how good its record actually is.

## Two kinds of count

| | What it covers |
| --- | --- |
| **Cycle count** | Locations you choose. The everyday count. |
| **Full inventory** | Every location this goods owner has, empty ones included. |

Both are started from **Warehouse > Cycle Count** on the desktop console, and both produce
the same kind of work for the floor.

## Starting a count

Press **New count** and fill in the form.

**Count type** is **Cycle count (selected locations)** or **Full inventory**.

For a **cycle count** you must give a scope, using any of:

- **Location IDs**, comma-separated, for example `1, 2, 3`.
- **Area ID**, to count every location in an area, for example `10`.
- **Location pattern**, matching location names, for example `A-01-%` for everything in that
  aisle. The `%` stands for "anything".

For a **full inventory** those fields disappear, because it owns its own scope. The form tells
you exactly what it will do: it counts every location this goods owner has, empty ones
included, and locks each of them until its order is finished.

**Campaign (optional)** groups related counts together so you can report on them as one piece
of work. Only open campaigns of the type you selected are offered, because a closed campaign
or one of the other type would be refused.

**Blind count** is on by default. Leave it on. A blind count does not show the operator what
Karyo expects to find, so what comes back is what is really there rather than a confirmation
of what was already believed. Turning it off produces agreement, not accuracy.

## What a full inventory does to your locations

A full inventory **locks every location it counts** until that location's count is finished.
Plan for that: locations being counted are not available for other work.

Locations that already have reserved stock, or an existing lock, are **skipped** rather than
forced. Starting the session reports which ones, once. **Write them down then**: that list is
not stored, so nothing shows it to you afterwards.

Skipped locations are not lost. Count them afterwards with a targeted cycle count in the same
campaign, once their reservations have cleared.

## Watching a count

Each session in the list shows **Open** or **Closed** and its progress, in the form
`3/10 done, 2 in review`. It does not show skipped locations: that list only exists in the
response to starting the session. If you did not note it, re-derive it by looking for the
locations in scope that got no count order.

**"In review"** means the operator has submitted a count that does not match the record, and
somebody has to decide what the truth is. That is the step where a human is required.

## Counting on the floor

### Work sent to you

A count arrives on the WORK tab like any other task.

1. **Scan the location.** The screen shows `AT <location>`.
2. **For each line**, scan the item, then enter what you counted on the keypad. Lot and serial
   are shown when the line has them.
3. Press **NEXT** through the lines, then **SUBMIT** on the last one.

Two situations get their own screen rather than a confusing one:

- **Nothing on record here.** The screen says so and offers a single **LOCATION EMPTY**
  button. Press it if the location really is empty. This is the only way to close such an
  order, which is why no misleading Submit is offered.
- **Every item here was already counted**, because it was resolved elsewhere while you were
  walking. The screen says so and lets you submit without re-entering anything.

**Enter what you actually count.** A count you fudge to match the system is worse than no
count: it launders a wrong record into a confirmed one.

If the count is cancelled while you hold it, **Release task** tells you it could not be
released because it may already be closed, rather than failing silently.

### Counting something you chose yourself

Choose **4 Count** from the MENU tab, scan a location, and press **START COUNT**. That starts
a blind count of that location and drops you straight into the counting screen.

Use this when you notice a discrepancy in the aisle. You do not have to wait for a supervisor
to schedule a count for a shelf you can see is wrong right now.

## Reviewing and closing

Counts that disagree with the record land in review on the desktop, where each line shows the
item, lot, serial and the **counted quantity**. Accepting a count writes the correction into
stock; the confirmation offers **Keep counting** as the way out if you opened it by mistake.

Corrections go through the inventory journal, so what changed, when, and on whose count is
recoverable afterwards. See
[inventory operations](../functional/inventory-operations.md).

## Next

- [Find stock](find-stock.md) to confirm the correction landed
- [The desktop console](desktop-console.md) to see accuracy move
