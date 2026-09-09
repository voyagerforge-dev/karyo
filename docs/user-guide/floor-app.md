# The floor app

The floor app is the operator surface: a progressive web app at `/m/` on the same site as the
console, built for a handheld scanner or a phone. Everything on it is large, one decision per
screen, and driven by scanning rather than typing.

Add it to your device's home screen once and it opens like an app.

## Two tabs: WORK and MENU

A bar across the bottom of every screen has exactly two tabs.

- **WORK** is your inbox. It is where work that has been planned for the warehouse comes to
  you.
- **MENU** is the numbered transaction list. It is where **you** decide to do something that
  nobody planned.

That distinction is the whole shape of the app. If a supervisor decided it, it is on WORK. If
you decided it, it is on MENU.

## WORK: the inbox

The inbox greets you by name and shows **My Work** with a count: tasks already claimed by
you. Tap any of them to resume where you left off.

The large button at the bottom is **GET NEXT TASK**. It asks Karyo for the next piece of work
you are entitled to do, claims it for you, and opens the right screen for it. You do not
choose from a list, and you do not need to know what type of work it is.

Three things it can tell you:

- **It opens a task.** Do the work.
- **"No work available right now."** There is nothing for you.
- **"Already taken - tap again."** Someone claimed it a moment before you did. Tap again.

Every task screen has a **Release task** button at the bottom. Use it when you cannot finish:
the task goes back to the pool for someone else. Releasing a count can fail if the count was
closed while you held it, and the app says so rather than pretending.

Work reaches you as one of several types: **Pick**, **Putaway**, **Move**, **Replenish**,
**Count**, **Receive**, **Transfer** and **Cross-dock**. From your chair they mostly behave
the same way, so this guide describes them by the job rather than the type.

## MENU: the numbered transactions

MENU lists up to eight numbered transactions. You only see the ones your role allows, and if
your role allows none the screen says *"No transactions available for your role."*

| | Transaction | What it does |
| --- | --- | --- |
| 1 | **Inquiry** | Scan a unit load or a location and see what is there. Changes nothing. |
| 2 | **Move** | Move a unit load to a location you scan, now. |
| 3 | **Receive** | Receive against an open advance shipping notice. |
| 4 | **Count** | Start a blind count of a location you scan. |
| 5 | **Pack** | Weigh and pack a picked unit load. |
| 6 | **Reprint** | Reprint a unit load's label. |
| 7 | **Sort** | Sort for wave fulfillment. **Needs a commercial engine.** |
| 8 | **Pack-out** | Cross-order pack-out. **Needs a commercial engine.** |

**Every menu transaction needs a connection.** If you are offline they appear dimmed with an
`OFFLINE` tag, and tapping one says *"Offline: menu transactions need a connection."* This is
deliberate: these transactions are you asserting something about the physical world that
Karyo must check against live data first.

## Scanning

Two kinds of scan field appear.

- **A confirming scan** knows what it expects and checks your scan against it. Scan the right
  label and the screen advances on its own. Scan the wrong one and it tells you so and stays
  put. This is what walks you through a pick or a move.
- **An open scan** does not know yet: it looks up whatever you scan. This is what Inquiry,
  ad-hoc Move and Reprint start with.

When a screen asks for a number instead of a scan, you get a large numeric keypad rather than
the device keyboard.

Screens that walk several lines show a step counter, so you always know how many are left.

## Working offline

The app keeps working when the connection drops, but not for everything, and it is precise
about which.

- **Tasks you already have** continue. Work you complete is queued on the device.
- **Menu transactions are blocked**, as above.
- **Signing in is blocked.** Reconnect first. Anything you queued survives.

When the connection returns, queued work is sent. If Karyo **rejects** something, that is
where a **Sync issues** screen matters: a red banner appears on the work inbox reading
*"N actions failed to sync - review"*. Tap it.

Each failed action is listed with what it was and why Karyo refused it. Your only action is
**Discard**. That is intentional: a rejection usually means the warehouse moved on without
you, for example the order was cancelled or someone else counted that location. Discard the
stale action, then look at the current state and redo the work if it still needs doing. If
you do not understand a rejection, show it to a supervisor before discarding it.

## Things the floor app deliberately does not do

- **No partial move confirmations.** A move on the floor moves the whole unit load. Partial
  quantities are a desktop action.
- **No resuming a paused task.** If a task was paused, the screen says *"Task is paused -
  resume it from the desktop board"* and offers only Release. Pausing and resuming are
  supervisor decisions.
- **No stock adjustments outside a count.** If the shelf disagrees with Karyo, count it.

## Next

- [Receive and put away](receive-and-put-away.md)
- [Pick an order](pick-an-order.md)
- [Count stock](count-stock.md)
