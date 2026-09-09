# Your first sign-in

## Karyo has two interfaces, and you belong on one of them

| | Desktop console | Floor app |
| --- | --- | --- |
| Address | the site root, `/` | `/m/` on the same site |
| Built for | a monitor, a keyboard and a mouse | a handheld scanner or a phone |
| Used by | planners, supervisors, administrators | operators moving goods |
| Looks like | dense lists, filters, detail panes | one large question per screen |
| Works offline | no | partly, see [the floor app](floor-app.md) |

Both are served by the same Karyo installation and act on the same warehouse. A supervisor
correcting an order on the console changes what the operator's scanner shows a moment later.

If your job is to move goods, bookmark `/m/`. If your job is to decide what gets moved,
bookmark `/`.

## Signing in

Karyo does not hold your password. Sign-in goes to Keycloak, the identity server that runs
alongside Karyo, and comes back to Karyo once you are authenticated.

- **On the desktop console**, opening any page sends you to the sign-in screen automatically
  and returns you to the page you asked for.
- **On the floor app**, you get a screen showing `KARYO` and a single **Log in** button. If
  the device is offline, that button is disabled and the screen says *"Offline. Reconnect to
  sign in."* along with *"Completed work stays queued until then."* You cannot sign in
  without a connection; work you already finished is not lost.

There is no default account. Your administrator creates your user in Keycloak and gives it
one or more roles. A production Karyo ships with no human credentials at all, on purpose.

## What your role lets you do

Karyo ships seven roles: five for people, and two for other systems. Each is a bundle of
finer permissions, and what you see is decided by the permissions in your bundle, not by the
role name.

| Role | Intended for | Can do |
| --- | --- | --- |
| `VIEWER` | anyone who needs to look | read inventory, locations, orders, products, tasks and reports |
| `RECEIVER` | goods-in | everything a viewer can, plus record receipts and change stock and orders |
| `OPERATOR` | floor staff | receive, move, pick, pack, count, and work the task list |
| `MANAGER` | supervisors and planners | everything an operator can, plus change layout, products and reports |
| `ADMIN` | the person who runs the installation | everything, plus user administration and integrations |
| `INTEGRATOR` | another system, not a person | read and write orders and products over the API |
| `AI_SERVICE` | Karyo's AI service account, not a person | read inventory, products, layout, orders and reports, and use the AI endpoints |

Two things follow from this that surprise people:

- **A missing menu entry is a permission, not a fault.** The desktop sidebar and the floor
  menu are both filtered by what your token actually carries. If a colleague sees
  **Users** and you do not, you are missing `user-admin`, not looking at a broken page.
- **`MANAGER` is not an administrator.** As shipped, only `ADMIN` reaches the `/admin`
  section. A manager runs the warehouse; an administrator runs Karyo.

The last two are for machines. Do not give `INTEGRATOR` or `AI_SERVICE` to a person: they
carry API access without the console permissions a job needs, and `AI_SERVICE` exists for the
copilot service account rather than for anyone signing in.

Your administrator can also compose roles differently for your site. If the table above does
not match what you can do, your installation was configured deliberately.

## Which goods owner am I looking at?

Karyo runs **one installation per operating company**. Inside it, a **client** is a goods
owner: whose stock this is. In a third-party warehouse that is your customer; in a warehouse
that only holds its own stock there is a single client and you can ignore the distinction.

You do not pick a goods owner when you sign in, and there is no tenant switcher. Your session
already knows which owner's data you are entitled to see, and every list you open is filtered
to it. If you expect to see stock that is not there, ask whether it belongs to a different
goods owner rather than assuming it is missing.

Being a goods owner is not a permission. The two are independent: your role decides what you
may do, the goods owner decides which stock you do it to.

## When something looks wrong

- **A page shows a padlock and "is a paid add-on".** That capability is a commercial engine
  this installation does not have. See [PAID-MODULES.md](../../PAID-MODULES.md); nothing is
  broken and no setting will turn it on.
- **A menu entry is missing.** See permissions, above.
- **A button does nothing but show a small "not wired yet" message.** A few controls in the
  console are placed but not yet connected. Where this guide meets one, it says so and gives
  you the route that does work.
- **The floor app says work failed to sync.** Open **Sync issues** from the red banner on the
  work inbox. See [the floor app](floor-app.md).

## Next

Go to [the desktop console](desktop-console.md) or [the floor app](floor-app.md), then work
through the task you actually need from [the index](README.md).
