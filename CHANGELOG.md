# Changelog

Every entry describes what changed in **this public distribution**: the source tree, its
documentation and its wiki. Releases are generated, so a release is one commit and one
matching tag, and earlier history is never rewritten.

Versions follow [semantic versioning](https://semver.org/spec/v2.0.0.html). The version in
`build.gradle.kts` is the canonical one and matches the tag.

## v2.0.2 - 2026-09-09

No warehouse behaviour changed.

### Added

- An **end-user guide** at [docs/user-guide](docs/user-guide/README.md), written for the people
  who run the warehouse rather than the people who install it: first sign-in and roles, the
  desktop console, the floor app, and one page per task for receiving and putaway, finding
  stock, picking, packing and shipping, replenishment, stocktaking and administration.
- A [code of conduct](CODE_OF_CONDUCT.md) (Contributor Covenant 2.1) with the reporting route.
- Issue templates for bug reports, documentation defects and feature requests, with a
  contact-link panel routing security reports and commercial-engine questions away from public
  issues, and a pull-request template that explains why this tree cannot merge external
  changes and what to do instead.
- This changelog, so a visitor can tell what changed between releases.
- Wiki pages the wiki previously lacked: getting started, a glossary, frequently asked
  questions, troubleshooting and release notes.

### Changed

- The wiki sidebar is grouped by who is asking - product and evaluation, using Karyo day to
  day, warehouse concepts, deploying and operating, technical reference, commercial engines -
  instead of one flat alphabetical list.
- The README carries licence, latest-release and CI badges.

## v2.0.1 - 2026-09-09

Documentation accuracy and supported-platform maintenance. No warehouse behaviour changed.

### Security

- Upgraded the supported application platform to Quarkus 3.33.3.2 (from 3.25.3) and Kotlin
  2.3.10 (from 2.1.20), taking the dependency fixes those releases carry.
- Rebased the nginx image on the 1.30 stable stream (from 1.27) and refreshed its Alpine
  packages during the build, so a fresh release image does not inherit stale cached layers.
- Moved the mobile build stage to Node 22, matching the declared Node floor.

### Added

- An **executable extension example** under
  `services/inventory-service/karyo-inventory-ext-example/`, with two stock filters that
  compile against the Apache-2.0 API modules only, plus their tests. The
  [implementer guide](docs/guides/implementer-guide.md#extend-the-free-application) now walks
  through building and installing it rather than describing extension points abstractly.
- Connected implementer workflows: a synthetic warehouse setup, a first inbound and outbound
  flow, and floor work, counts and replenishment, each verifiable before real stock is used.
- A recorded product walkthrough in the README, and a stated purpose and problem statement.

### Fixed

- An install with an empty `KARYO_LICENSE` value now boots. The supported environment
  template sets the variable empty, and the configuration layer treats an empty value as
  absent, which previously stopped the free application from starting.
- Repaired wiki navigation and several document links that resolved to pages this
  distribution does not contain.
- Reconciled API standards, extensibility, observability, webhook catalog and the functional
  specifications against the behaviour actually implemented in this tree.
- Corrected deployment and cloud-deployment instructions that no longer matched the shipped
  Compose files and scripts.

### Removed

- The architecture decision record collection is no longer published. It is internal design
  history rather than guidance for running or extending Karyo, and inbound references were
  repointed at current documentation. The
  [requirements register](docs/REQUIREMENTS.md) remains public.

## v2.0.0 - 2026-09-07

First public release of Karyo WMS.

### Added

- The complete free warehouse management application under
  [Apache-2.0](LICENSE): products and warehouse layout, receiving and quality holds,
  inventory and putaway, delivery orders, discrete picking, packing and shipping,
  replenishment, stocktaking, work allocation, goods-owner and user administration,
  reporting, webhooks and a document archive.
- A desktop console for planners and administrators and a floor progressive web app for
  operators, both served by one Quarkus application over one PostgreSQL schema.
- A four-container Compose deployment (application, PostgreSQL, Keycloak, nginx) with
  [DEPLOY.md](DEPLOY.md) covering installation, reset, maintenance, backup and restore.
- Public extension APIs, service provider interfaces, CDI events, strategy properties and
  webhooks, documented in
  [extensibility architecture](docs/architecture/extensibility-architecture.md).
- An honest boundary for the nine optional commercial engines in
  [PAID-MODULES.md](PAID-MODULES.md): what each one does, what it does not do, and what the
  free application shows in its place.
