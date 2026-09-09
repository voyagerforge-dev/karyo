# Administer Karyo

This page is for the person who sets Karyo up and keeps it right: goods owners, users, the
warehouse layout, and the settings that change how Karyo behaves.

Most of it needs the `user-admin` permission, which as shipped only the `ADMIN` role carries.

## Goods owners (clients)

**Admin > Clients** is where goods owners live. A client is *whose stock this is*. In a
warehouse holding only its own goods there is one; in a third-party warehouse there is one
per customer.

Press **New client** and fill in:

| Field | Notes |
| --- | --- |
| **Number** | The client's identifier, for example `CL-100`. **It cannot be changed afterwards**, so agree it before you save. |
| **Code** | A short code of your own. |
| **Name** | The display name, for example `Acme Corp`. |
| **Email**, **Phone**, **Fax** | Contact details. |

The list is **searched, not filtered**. One search box matches a client's name or number, and
there are no state chips: the list cannot be narrowed to Active or Inactive. Above it sit three
read-only counts - total clients, **Active** and **Inactive** - and each row carries its own
**Active** or **Inactive** pill, with a **System** pill on Karyo's own client. To review the
deactivated ones, read the pills down the list.

Deactivate a client rather than deleting one: their stock, orders and history all reference
them.

The **System** client is Karyo's own. It is not an administrator and not a customer, and you
should not use it as one.

**Clients are not tenants.** Karyo runs one installation per operating company. A separate
company means a separate installation, not another row here. The **Tenants** entry in the
admin sidebar is a placeholder for a future concept and does nothing today.

## Users

**Warehouse > Users** manages the people who sign in. The list filters to **All**, **Active**
and **Deactivated**, and each row shows the person's name, whether they are active, their
email, and the roles they hold.

Press **New user**:

| Field | Notes |
| --- | --- |
| **Username** | How they sign in. |
| **First name**, **Last name**, **Email** | Identity. |
| **Password** and **Force password change** | Set a temporary password and require them to change it. Use this. |
| **Roles** | What they may do. See [your first sign-in](first-sign-in.md) for what each role carries. |
| **Goods owner** | Which client this person belongs to. |
| **Tenant authority** | See below. Get this one right. |
| **Warehouse ID** | Which warehouse they work in. |

Karyo has no user store of its own: this page writes to Keycloak. **Admin > Users and roles**
opens the Keycloak admin console directly in a new tab, for anything this page does not cover
such as identity federation or password policy.

### Tenant authority, and why it matters

**Tenant authority** decides what a person can *see*, and it is independent of their role.

- **OPS** is operating-company staff. In a warehouse handling several goods owners, they
  physically touch everyone's stock, so their view spans all owners.
- **OWNER** is a goods owner: someone from the customer. They see only their own stock.

Two things follow, and both are safety properties rather than conveniences:

1. **Role and tenant authority are separate questions.** A person can be an `ADMIN` and still
   be an OWNER, seeing only one client's data. Being a goods owner is not a permission level.
2. **Anything unrecognised is treated as OWNER**, the restrictive option. If you leave this
   unset, Karyo restricts rather than exposes. That is deliberate, and it means a
   misconfiguration shows up as somebody seeing too little, never too much.

If a user reports missing stock, check their tenant authority and goods owner before you
assume data loss.

## Warehouse layout

**Warehouse > Locations** is every place stock can be. The list filters to **All**, **Pick
face**, **Reserve** and **Blocked**.

**New location** opens a form whose fields fall into four groups.

**Identity and place**

| Field | Notes |
| --- | --- |
| **Name** | What people call it and what gets scanned. Choose a scheme and keep to it. |
| **Description** | Free text. |
| **Scan code** | An alternative code, if the barcode differs from the name. |
| **Rack**, **Section**, **Field** | The physical coordinates in your racking. |
| **X, Y, Z position** | Positions used to order picking walks sensibly. |
| **Order index** | An explicit sort order. |

**Behaviour**

| Field | Notes |
| --- | --- |
| **Location type** | The kind of place this is. |
| **Zone**, **Overflow zone**, **Temperature zone** | Grouping, spill-over and cold-chain constraints. |
| **Kind**, **Usages** | What this location may be used for. |
| **Handling class** | Matches goods to places that can take them. |
| **Capacity** | How much fits. Enforced, so a wrong value causes real refusals. |

**Exceptions**

| Field | Notes |
| --- | --- |
| **Exclude from automatic putaway** | Karyo will never choose this location on its own. Operators can still put stock there deliberately. |
| **Clearing location** | Marks a location that gets emptied rather than stocked. |
| **Block reason** | Blocks the location, with a stated reason. |

**Automation**

**PLC code** is the address of the location in an automation system, if you have one.

**Block rather than delete.** Deleting a location that stock has been in loses the history
that explains where things went.

### Pick faces and replenishment

A **fix assignment** binds a product to a location with a minimum and a target quantity. That
is what lets [replenishment](replenish.md) know a pick face is running down. A pick face
without one is invisible to replenishment, no matter how empty it gets.

### Locations that fulfilment needs

Two locations are load-bearing and worth setting up before you start shipping:

- A **pack staging** location. Without it, **Release to picking** refuses with *"no
  PACK_STAGING location configured"*.
- A **ship staging** dock, which dispatch moves shipping units to.

## Products and unit loads

**Warehouse > Items** holds products. Its filters are **All**, **Class A**, **Class B** and
**Class C**, which are ABC classes produced by the **Slotting** commercial engine rather than
anything you set on a product. Without that engine no item carries a class, so those three
filters match nothing and **All** is the only useful one. See
[PAID-MODULES.md](../../PAID-MODULES.md).

**Admin > Unit load types** defines the pallets, cartons and totes your warehouse uses. These
are the types offered when receiving and packing, so define them to match what is actually on
your floor.

## Strategies

**Warehouse > Strategies** holds **order strategies** and **storage strategies**: how Karyo
chooses stock to allocate, and where it puts stock away.

Two entries on the order strategy form are marked as needing a commercial engine and will not
work without one: the advanced fulfillment option, and **CARTONIZATION**. With cartonization
absent, packing falls back to one-to-one pack-out, which is the ordinary free behaviour rather
than a failure. See [PAID-MODULES.md](../../PAID-MODULES.md).

The rules themselves are specified in [stock selection](../functional/stock-selection.md) and
[location finding](../functional/location-finder.md).

## System properties

**Admin > System properties** holds runtime settings. A setting is resolved in a fixed order:
a value set for a specific goods owner wins, then a system-wide value, then the deployment's
configuration, then Karyo's built-in default.

The practical consequence: **a value set here can override what the deployment's environment
says**. If a setting is not behaving as your deployment configuration suggests, look here
before you look at the server.

To put a key back on the value behind it, **delete the stored row** rather than trying to blank
it. Deleting is what makes the next rung of the ladder apply again.

Several capabilities are deliberately **off by default** and have to be turned on here,
including automatic replenishment scanning and demo data. Do not enable one casually on a
live installation.

## Watching and proving

| Page | What it is for |
| --- | --- |
| **Admin > Health** | Whether the application and its dependencies are up. |
| **Admin > Audit log** | Who did what. The first place to look when a record changed and nobody knows why. |
| **Admin > Documents** | The archive of generated documents, so a delivery note produced weeks ago can be produced again unchanged. |
| **Admin > Integrations** | Webhook endpoints and their delivery. See the [webhook event catalog](../integration/webhook-event-catalog.md). |
| **Admin > Extensions (SPI)** | The extension points this build exposes and what is plugged into them. |

## What is not here

**Copilot**, **Tenants** and **Feature flags** appear in the admin sidebar greyed out with a
*"Coming soon"* tooltip. They have no backend and cannot be opened. They are shown rather than
hidden so the admin surface does not pretend to be smaller than it is planned to be.

## Next

- [Your first sign-in](first-sign-in.md) for what each role can do
- [The desktop console](desktop-console.md) for the rest of the admin section
- [DEPLOY](../../DEPLOY.md) for installation, backup, restore and upgrades
