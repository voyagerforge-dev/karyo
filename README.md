# Karyo WMS

**Know what arrived, where it is, and what needs to ship.**

Karyo is a self-hosted warehouse management system for warehouse teams and the developers who
support them. It connects receiving, stock locations and floor work with picking, packing and
shipping, through a desktop console and a mobile progressive web app (PWA).


![Karyo inventory walkthrough: find a synthetic product, inspect its stock and location, then return to the inventory list.](docs/media/inventory-walkthrough.gif)

For the connected receiving-to-shipping journey, follow the [written walkthrough](docs/guides/implementer-guide.md#first-inbound-and-outbound-flow).

## Why Karyo exists

Warehouse teams need answers to practical questions: Did the expected goods arrive? Which stock
is available rather than reserved or on hold? Where should it go? What should an operator pick
next? Spreadsheets and disconnected tools make those answers difficult to keep consistent as
receipts, movements and orders change.

Karyo exists to make capable warehouse software more accessible and understandable. Modern
open-source databases, application frameworks and browser tooling make excellent technology
available without building everything from scratch. Useful technology does not have to start
with expensive licences or unnecessary infrastructure. Our aim is to reduce avoidable complexity
and cost, not to promise zero-cost operation: hosting, devices, integration, training, backups
and support still matter.

### Simple where it helps, explicit where it matters

**KISS (Keep It Simple, Stupid)** means choosing the clearest design that does the job, not ignoring
warehouse complexity. **YAGNI (You Aren't Gonna Need It)** means adding machinery for a demonstrated
need, rather than a hypothetical future one. In Karyo today:

- **One application, clear modules.** Quarkus assembles the backend into one process using one
  PostgreSQL application schema. Internal calls use typed interfaces and in-process events,
  without network hops between warehouse modules. Modules still have contracts and boundaries;
  they deploy together rather than scaling independently.
- **A practical deployment.** Compose runs the application, PostgreSQL, Keycloak and nginx.
  Desktop and floor interfaces share the backend. There is no required Kubernetes cluster,
  Kafka broker or Redis service. Four containers and two interfaces still need real operations.
- **Ordinary state, purposeful events.** Database records hold current warehouse state; an
  inventory journal tracks movements and an active outbox supports webhook delivery. There is
  no event-replay system to operate just to find stock. Transactions and retries still need care.
- **Optional means optional.** The free warehouse works without an AI provider or commercial
  engine. Add extensions for an actual requirement through existing APIs at build time, rather
  than a runtime plugin platform. Rebuilding is the tradeoff for that simpler deployment boundary.

See the [current architecture](https://github.com/voyagerforge-dev/karyo/wiki/Technical-Architecture-Overview)
and [extension contracts](docs/architecture/extensibility-architecture.md) for details and limits.

## What you can do

The free application includes products and warehouse layout, receiving and quality holds,
inventory and putaway, delivery orders, discrete picking, packing/shipping, replenishment,
stocktaking, work allocation, goods-owner/user administration, reporting, webhooks and a document
archive. It suits teams evaluating a self-hosted WMS and implementers willing to configure and
verify it for their warehouse, not an unconfigured drop-in replacement for every operation.

**Open core, with an explicit boundary:** all Karyo source here, including public extension APIs,
is [Apache-2.0](LICENSE). Nine optional commercial engines are not included. Ordinary document
generation and one-to-one pack-out remain free. [Commercial engines](PAID-MODULES.md) owns the
capability list, prerequisites, limitations and contact route. Confirm commercial delivery
availability there before relying on it; a licence token cannot install absent engine code.

## Get started

To build the backend from source, install a **JDK 21 compiler**, set `JAVA_HOME`, and use the
checked-in wrapper:

```bash
git clone https://github.com/voyagerforge-dev/karyo.git
cd karyo
./gradlew :services:karyo-app:quarkusBuild
```

That builds the backend, not a running warehouse. For a complete installation you also need
Node.js 22.12+, Python 3, and Docker with Compose or Podman with podman-compose:

1. Follow [DEPLOY](DEPLOY.md#quick-start) to configure the exact browser origin and independent
   secrets, build the four-container stack and establish the first administrator. Production
   has no default human login. Use an isolated target and synthetic data first.
2. Follow the [implementer guide](docs/guides/implementer-guide.md) to configure a warehouse and
   verify receiving, putaway, picking and shipping before using real stock.
3. Developing locally or running tests? Use [developer onboarding](docs/guides/developer-onboarding.md).
   Extending the free application? Start with the [executable example](docs/guides/implementer-guide.md#extend-the-free-application).

## Documentation

- [Product and workflow wiki](https://github.com/voyagerforge-dev/karyo/wiki)
- [Requirements register](docs/REQUIREMENTS.md)
- [Stock selection](docs/functional/stock-selection.md), [location finding](docs/functional/location-finder.md)
  and [picking](docs/functional/picking.md)
- [API standards](docs/architecture/api-standards.md) and [webhook event catalog](docs/integration/webhook-event-catalog.md)
- [Operations, backups and upgrades](DEPLOY.md)

## Help, issues and contributions

[Issues](https://github.com/voyagerforge-dev/karyo/issues) are welcome for reproducible bugs,
documentation problems and feature requests. Describe the warehouse problem and include sanitized
reproduction steps. See [CONTRIBUTING](CONTRIBUTING.md) for the reporting and extension routes.
**External pull requests are not accepted:** this is a generated distribution. The first release
has one root commit; each later release adds one generated commit and matching tag without
rewriting earlier history. Forks and independent changes are permitted under Apache-2.0.

**Security problems:** report vulnerabilities privately through [SECURITY.md](SECURITY.md), not
in a public issue. Never attach credentials, licence tokens or customer records to a report.

## Licence and lineage

See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](THIRD-PARTY-NOTICES.md) for
licensing and attribution. Karyo's functional ancestor is **myWMS**, the GPL-3.0 Java EE warehouse
management system. Karyo is not a port or translation of myWMS: it retains familiar warehouse
concepts while diverging in architecture, user experience, deployment, extensions and selected
workflow defaults.
