# Implementing a Karyo warehouse

This is the current route from the public source distribution to a working free warehouse and
an augmented application. It is not a private maintainer handbook. Use synthetic data until the
installation, access boundaries and workflows have been verified.

## What you are installing

Karyo is one Quarkus modular monolith, PostgreSQL, Keycloak and nginx. The same backend serves
the React desktop application and the floor PWA at `/m/`. There is no runtime Kafka dependency.
The normal free installation needs neither a commercial licence nor an AI provider.

Included free workflows cover products/layout, receiving and quality holds, inventory and
putaway, delivery orders, discrete picking, packing/shipping, replenishment, counting, work
allocation, goods-owner/user administration, reporting, webhooks and the document archive.
The conversational copilot is optional; `KARYO_AI_PROVIDER=none` leaves normal work available.
Leave `KARYO_DEMO` off in a real warehouse.

Public `*-api` modules are Apache-2.0, including contracts for optional commercial engines.
An interface, a licence token or a visible menu entry cannot install missing engine code.
See [Commercial engines](../../PAID-MODULES.md) for capabilities, prerequisites, current limits
and acquisition. Ordinary document generation and one-to-one pack-out remain free.

### Build and schema closure

The supplied `settings.gradle.kts` owns the public module graph; `services/karyo-app/build.gradle.kts`
owns application assembly. Build that graph intact, including its supporting API modules and shared
libraries, alongside both frontends. Optional demo/example sources do not imply activation.

The supplied schema is intentional support, not a list of installed engines. Keep all supplied
migrations and their registered locations. Free order/fulfillment contracts retain wave, batch,
streaming and consolidation columns; free demo/reset paths also use supporting tables. A table
whose name resembles an add-on does not install that add-on. Do not delete tables or migrations
based on their names.

The first distribution targets an empty database. Keep released migration checksums and versions
unchanged on subsequent upgrades, including comments. Interleaved module version bands require
out-of-order application. Do not repair, rebaseline or renumber a database to hide a mismatch.
Conversion of an existing private/commercial database, or a free-to-commercial database upgrade,
is not promised without a separately documented and tested compatibility path.

## Install and establish access

1. Install a **JDK 21 compiler**, Node.js 22.12+ and Docker with Compose or Podman with podman-compose.
   Use the checked-in wrapper. Set `JAVA_HOME` to a JDK, not a JRE.
2. Follow [DEPLOY](../../DEPLOY.md) for the supported environment file, exact public origin,
   HTTPS, four-container build, service secrets and bootstrap procedure. Ordinary clone files
   must remain readable by bind-mounted containers; use `umask 022` for source/build inputs.
   Keep the enclosing private evidence directory private and secret files mode `0600`.
3. The production realm contains **no human users**. Provision the first application administrator
   using externally supplied temporary Keycloak bootstrap credentials. Verify application login
   and user administration, delete the temporary bootstrap user, then remove both bootstrap
   assignments from the environment file. Redeploy and verify those credentials no longer work.
4. Create the warehouse's actual users and roles. Keep the permanent user-management service
   account separate from the audit service account. Never broaden service roles to make a test pass.
5. Confirm desktop login/logout, a protected `/inventory` deep link and a protected `/m/` deep link.
   Reaching `/q/health/ready` is not an authentication or warehouse-workflow test.

Do not deploy over an existing stack without identifying its project, ports, storage and credentials.
Do not reset shared volumes. An initialized database retains its credentials; changing the env file
is not a database-password reset. [DEPLOY](../../DEPLOY.md) owns rotation and realm maintenance.

For an isolated rehearsal on a shared runtime, follow
[Select the deployment](../../DEPLOY.md#select-the-deployment). Retain that target's exported values
and matching environment file for every deploy/restart/test invocation below.

For local development and container-backed tests, use the
[developer onboarding guide](developer-onboarding.md). The public build and deterministic suites
can be exercised from a clean clone with:

```bash
./gradlew assemble test --no-build-cache
(cd frontend/web && npm ci && npx vitest run && npm run build)
(cd frontend/mobile && npm ci && npx vitest run && npm run build)
(cd tests/e2e && npm ci && npm run test:helpers)
```

Podman-backed tests need its user socket enabled, `DOCKER_HOST` pointing to that socket and
`TESTCONTAINERS_RYUK_DISABLED=true`. Parallel worktrees should use `QUARKUS_HTTP_TEST_PORT=0`.
Read the executed **and skipped** counts, not only Gradle's exit status. Live provider, printer,
receiver and deliberately augmented-image checks are separate from the default test suite.

## Configure one synthetic warehouse

Use a unique suffix for each rehearsal. Do not load these examples into an operating warehouse.
Work as a user with the required warehouse roles and the intended goods-owner scope.

| Order | Configure | Example and observable result |
|---|---|---|
| 1 | Goods owner and identities | A synthetic owner and its operator/manager; verify the owner attributes and roles in Keycloak. An OWNER principal must not read another owner's goods. |
| 2 | Units and products | Choose an existing item unit; create `GUIDE-WIDGET-<suffix>`, with weight and packaging definitions appropriate to the exercise. |
| 3 | Location/unit-load types | Create a shelf type and pallet/cart type with realistic dimensions and carrying limits. |
| 4 | Areas and locations | Create receiving, storage, pick, pack-staging and ship-staging locations with the correct area usages and scannable names. |
| 5 | Strategies | Choose the receiving, storage and order strategy deliberately; inspect flags rather than assuming every strategy is identical. |
| 6 | Replenishment/count setup | Configure a fixed pick-face assignment with a minimum and maximum; prepare a separate count location with known physical contents. |

`client_id` identifies the goods owner inside an instance, not a permission to impersonate that
owner. OPS scope and OWNER scope differ. Validate ordinary ownership and shared system-master-data
behavior before importing real stock. Do not use a privileged account to conceal missing operator
permissions. [API standards](../architecture/api-standards.md) covers JSON, errors and pagination.

## First inbound and outbound flow

Follow this connected route in the desktop application, checking the expected state after each step:

1. **Receive:** create an ASN for the synthetic item, release it, create/select a goods receipt and
   receive a line onto a labelled unit load at the receiving location. Confirm stock quantity, lot
   and container identity. A quality hold should remain visible and must prevent ordinary picking.
2. **Put away:** complete the generated transport work to the chosen storage location. Confirm the
   unit load's location and the movement journal. If no destination is found, inspect location
   eligibility and strategy configuration; do not manually bypass a safety lock.
3. **Order:** create a delivery order for less than the available, unlocked quantity. Release it.
   Check line reservation/shortage and stock `availableAmount = amount - reservedAmount`.
4. **Pick:** release the delivery order to picking, claim the work and confirm the picked quantity
   onto the target unit load. `POST /api/v1/pick-orders` returns an array, not a single object.
5. **Pack and ship:** open packing, pack the picked goods, inspect ordinary PDF/ZPL outputs,
   manifest, then dispatch. Confirm final order/stock state and journal attribution. Manifest alone
   is not dispatch. A downloaded label is not proof that a hardware printer received it.
6. **Exercise a failure:** request more than unlocked stock or use a held lot. Verify an explicit
   shortage/refusal, nonnegative available stock, and no unauthorized reservation.

The existing [picking](../functional/picking.md), [stock selection](../functional/stock-selection.md)
and [location finding](../functional/location-finder.md) references explain the decision rules.
The reproducible API-driven worked dataset is in
[scenario helpers](../../tests/e2e/fixtures/scenario-helpers.ts), with executable workflows in
[the scenario suite](../../tests/e2e/tests/scenarios.spec.ts). Those helpers create unique synthetic
records, drive real public endpoints and check resulting state; they are not a production importer.

## Floor work, counts and replenishment

Sign in at `/m/` using the operator's real roles. Claim ordinary work, scan the required identity,
complete it and verify the desktop result. Menu transactions are online-only; do not assume all
work can be queued offline. Sort and cross-order Pack-out require the optional commercial engine.

For a blind count, create a count session for the prepared location, record the observed physical
quantity, then have the authorized user accept or recount the difference. Verify stock, locks and
the journal afterward. For replenishment, reduce a configured pick face below its minimum, invoke
a scan, execute the resulting transport and confirm source/destination quantities. Automatic scans
are opt-in. See [replenishment](../functional/replenishment.md) for whole-load versus top-up behavior.

## Run browser acceptance

Production-realm tests need ephemeral role identities and their explicit secrets. The supported
runner provisions and cleans up test identities when configured; it does not invent working
production credentials. Read the runner's required variables before starting:

```bash
./scripts/run-e2e.sh --help
# Configure the named secrets and target from that help, then run on disposable data:
BASE_URL=http://localhost:18088 ./scripts/run-e2e.sh scenarios.spec.ts
```

The example above assumes the isolated localhost deployment in
[Select the deployment](../../DEPLOY.md#select-the-deployment). Remote targets
additionally require `KARYO_E2E_ALLOW_REMOTE_PROVISION=true`, an exact
`KARYO_E2E_EXPECTED_ORIGIN`, and an explicitly supplied `KEYCLOAK_ADMIN_CLIENT_SECRET`.

Use an isolated target with unique storage/ports. Do not reuse a failed initialized database with
new credentials. The scenario suite writes synthetic warehouse records. Preserve private evidence
and remove credentials from anything attached to an issue. Missing credentials or infrastructure
must fail visibly, not turn the acceptance suite into skipped tests. The optional direct database
sweep additionally requires an explicit `KARYO_PG_CONTAINER` and the matching
`KARYO_CONTAINER_CLI` (`podman` by default). Leave that container unset to disable the sweep;
unique scenario records still allow the actual browser tests to run. Never point it at shared data.

## Extend the free application

You may build your own Apache-2.0 extended image. No vendor approval is needed for that free build.
Commercial combined images and their contracts are a separate vendor-delivery path.

The executable example is
[`HeldLotStockFilter`](../../services/inventory-service/karyo-inventory-ext-example/src/main/kotlin/com/karyo/inventory/ext/example/HeldLotStockFilter.kt).
It implements the real public `StockSelectionFilter` SPI and uses `StockUnitLookup` from the same
API module. It removes synthetic lots beginning with `EXAMPLE-HOLD-`, keeps the remaining FIFO
order, reads candidates in one explicitly owner-scoped batch, and never adds new candidates.
This is **not hazmat enforcement, a quarantine system or a production safety policy**. The older
`HazmatStockFilter` is preserved in the same example JAR as an inactive CDI alternative. It has no
activation priority and is not enabled by the augmented build.

The example JAR carries its CDI discovery marker. It is **not** included in the normal app.
Enable it deliberately during augmentation in a disposable demonstration clone:

```bash
./gradlew :services:inventory-service:karyo-inventory-ext-example:test --no-build-cache
./gradlew -PkaryoInventoryExample=true :services:karyo-app:quarkusBuild
```

To include it when using the normal build/deployment script, export the corresponding Gradle
project property only for that explicit invocation:

```bash
ORG_GRADLE_PROJECT_karyoInventoryExample=true ./scripts/deploy-server.sh
```

This rebuilds and restarts the script's target stack: establish that it is your disposable target
first. Keep its existing credentials on restart. Copying a JAR next to a running Quarkus process
is not installation. Both discovery and behavior require the augmented application/image, not
merely a compiled extension JAR.

Prove the observable effect, using the same browser credentials/target prepared above:

```bash
./scripts/run-e2e.sh --config=playwright.operational.config.ts inventory-extension.operational.ts
```

Using the runner retains its ephemeral-user provisioning and credential handling; invoking npm
or Playwright directly instead requires supplying those same prerequisites explicitly.
The proof signs in through Keycloak, finds `HeldLotStockFilter` in the live ADMIN registry, verifies
that `HazmatStockFilter` is absent, then acts as a warehouse manager. It creates a unique SKU with three 10-unit stocks: oldest synthetic
held lot, ordinary locked stock, and ordinary unlocked stock. Releasing demand for 15 must reserve
only the 10 unlocked ordinary units and report a shortage of 5. Held and locked stock retain zero
reservations, and available amounts remain nonnegative.

For the negative control, rebuild/redeploy the **same disposable database and credentials** without
the Gradle example property, then run:

```bash
KARYO_EXPECT_INVENTORY_EXAMPLE=false ./scripts/run-e2e.sh --config=playwright.operational.config.ts inventory-extension.operational.ts
```

This creates fresh unique records and requires the registry to omit both example implementations. Ordinary FIFO now
reserves 10 from the oldest lot and 5 from the other unlocked stock; locked stock remains untouched.
The default augmented proof must fail against a stock image, not silently pass without installation.

For a real extension, use your own package/JAR and API-only compile dependencies. Add that JAR to the
application's build classpath before augmentation, retain CDI discovery, and test its actual effect
and safety invariants. Do not copy illustrative interfaces from historical architecture examples as
if they were released APIs. A paid-engine API extension also needs the installed commercial engine
to execute. Keep the unextended free build usable independently.

## Operate and integrate

- Follow [DEPLOY](../../DEPLOY.md) for backups, restore rehearsal, upgrades, secret rotation and
  realm maintenance. Verify restore into isolated storage before treating a backup as usable.
- Inspect readiness/health and structured logs. Metrics are exposed, but an exposed endpoint does
  not mean a monitoring or tracing stack has been deployed.
- Use the [webhook catalog](../integration/webhook-event-catalog.md) for event contracts. Test an
  actual receiver before claiming delivery. Protect webhook credentials and use synthetic payloads.
- Configure printers/carriers/providers explicitly. Distinguish downloaded documents, mocks and
  successfully delivered external effects. Keep optional AI disabled until its provider is ready.
- Record your deployed release, image identities, configuration and tested restore procedure without
  committing secrets. The requirements register and ADR collection remain useful context, including
  historical material, while this guide and DEPLOY own the current installation route.
