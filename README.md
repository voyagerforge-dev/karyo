# Karyo WMS

Karyo is a self-hosted warehouse management system for receiving, inventory, putaway, picking,
packing, shipping, replenishment, and stocktaking. It runs as one Quarkus modular monolith with
PostgreSQL and Keycloak, plus React interfaces for warehouse planners and floor operators.

## Public repository and license

All source in this public repository is licensed under [Apache-2.0](LICENSE). It contains the
complete free application, public extension contracts, tests, both user interfaces, and the
four-container deployment path.

Karyo uses an open-core model. Every public `*-api` module remains Apache-2.0, including the API
contracts for commercial engines. The corresponding commercial `*-core` implementations are not
included in this repository. You may build your own extended free image using the
[implementer guide](docs/guides/implementer-guide.md). Extensions are included during Quarkus
build-time augmentation, not uploaded to a running application.

Commercial combined images are a separate vendor-delivered product. The signed-licence delivery
design uses short-lived download URLs, with no registry account or second customer identity.
Confirm availability before relying on that service: live commercial delivery is not yet proven.
Commercial source and private build inputs are not distributed. See
[Commercial engines](PAID-MODULES.md) for prerequisites, limits and the contact route.

## Lineage

Karyo's functional ancestor is **myWMS**, the GPL-3.0 open-source Java EE warehouse management
system. Karyo is not a port or a translation of myWMS.

Karyo retains familiar warehouse concepts while it
deliberately diverges in architecture, user experience, deployment, extension boundaries, and
selected workflow defaults.

## Build from a clean clone

Install a JDK 21 compiler, Node.js 22, and Docker or Podman. Use the checked-in Gradle wrapper, not
a separately installed Gradle release:

```bash
./gradlew :services:karyo-app:quarkusBuild -x test
./gradlew :services:karyo-app:test --tests "com.karyo.common.PatchableTest"
```

The [developer onboarding guide](docs/guides/developer-onboarding.md) covers prerequisites,
container-backed tests, frontend builds, local development, free-tier deployment, and extension
development. For a production installation, continue with [DEPLOY.md](DEPLOY.md).

## Documentation

Start with the [implementer guide](docs/guides/implementer-guide.md): installation, warehouse
setup, inbound/outbound operation, floor work, extension augmentation and operations. It describes
the current four-container application. The requirements register and ADR collection remain
available for context, including historical decisions; they are not all current deployment recipes.

- [Requirements register](docs/REQUIREMENTS.md)
- [Functional stock selection](docs/functional/stock-selection.md)
- [Functional location finding](docs/functional/location-finder.md)
- [Functional picking](docs/functional/picking.md)
- [API standards](docs/architecture/api-standards.md)
- [Webhook event catalog](docs/integration/webhook-event-catalog.md)
- [Architecture decision records](docs/architecture/decisions/README.md)
- [Extensibility architecture](docs/architecture/extensibility-architecture.md)

## Issues and contributions

Issues are welcome for reproducible bugs, documentation problems, and feature requests.

Pull requests are not accepted because this repository is generated from Karyo's working
repository. The first release has one root commit; each later release adds one generated commit
and matching tag without rewriting earlier history. Each release regenerates the source tree,
so changes made only here would be overwritten. Apache-2.0 permits you to fork the public tree
and maintain your own changes; use an issue when you want a change considered for a future
generated release.
