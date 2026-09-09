# Developer Onboarding

This guide starts from a clean clone of Karyo's public repository. Karyo is a Kotlin and Quarkus
modular monolith with two React frontends. The Gradle project `:services:karyo-app` is the only
backend application that runs or ships; the domain projects under `services/` are libraries in
that application.

## Public repository scope

Everything in the public repository is licensed under Apache-2.0. The free application, its tests,
all extension API modules, and a worked inventory extension are present. Commercial engine API
contracts are also present, but their implementation modules are not. A license key cannot add
code that is absent from a source build. See [Commercial engines](../../PAID-MODULES.md) for the
boundary and the customer delivery model.

The commands below use only files in the public clone. Run them from the repository root unless a
section says otherwise.

## Prerequisites

| Tool | Required version | Used for |
|---|---:|---|
| JDK, including `javac` | 21 | Backend compilation and tests |
| Node.js and npm | see [DEPLOY](../../DEPLOY.md#prerequisites) | Web and floor-PWA builds |
| Python | 3.x | Deployment configuration validation; the deploy script aborts without it |
| Docker with Compose, or Podman with `podman-compose` | Current stable release | Dev Services, integration tests, and deployment |
| Git | 2.x or newer | Source control |

Confirm that Java points to a full JDK before running Gradle:

```bash
java -version
javac -version
node --version
npm --version
python3 --version
```

Both Java commands must report version 21. If several JDKs are installed, set `JAVA_HOME` to the
JDK 21 installation for your operating system and put `$JAVA_HOME/bin` first on `PATH`.

## Bootstrap and build

The Gradle wrapper is committed to the repository. Do not install a separate Gradle release.
Bootstrap it first:

```bash
./gradlew --version
```

Build the free backend application without running tests:

```bash
./gradlew :services:karyo-app:quarkusBuild -x test
```

The runnable Quarkus fast-jar is written to `services/karyo-app/build/quarkus-app/`.

Install locked frontend dependencies and build both user interfaces:

```bash
npm ci --prefix frontend/web
npm run build --prefix frontend/web
npm ci --prefix frontend/mobile
npm run build --prefix frontend/mobile
```

## Tests

A focused JVM test does not need a container runtime:

```bash
./gradlew :services:karyo-app:test --tests "com.karyo.common.PatchableTest"
```

Quarkus integration tests start PostgreSQL and Keycloak through Dev Services. With Docker running,
execute a focused integration test with:

```bash
./gradlew :services:karyo-app:test \
  -Dquarkus.http.test-port=0 \
  --tests "com.karyo.inventory.api.v1.HealthResourceTest"
```

For rootless Podman on Linux, start its API socket and point Testcontainers at the current user's
socket:

```bash
systemctl --user start podman.socket
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew :services:karyo-app:test \
  -Dquarkus.http.test-port=0 \
  --tests "com.karyo.inventory.api.v1.HealthResourceTest"
```

Run the complete backend suite only after the focused integration test succeeds. Keep the exported
Podman variables in the same shell when using Podman:

```bash
./gradlew :services:karyo-app:test -Dquarkus.http.test-port=0
```

The complete suite is large and the test worker is configured for up to 5 GB of heap. Use the
focused form during normal development.

## Run in development mode

Start the backend from the application project, not from an individual domain module:

```bash
./gradlew :services:karyo-app:quarkusDev
```

Quarkus Compose Dev Services starts PostgreSQL and Keycloak from
`services/karyo-app/src/main/resources/compose-devservices.yml`. The API listens on
`http://localhost:8080`. Stop dev mode with `Ctrl-C`; Quarkus then stops the containers it started.

The frontend development servers are optional. In separate terminals, run:

```bash
npm run dev --prefix frontend/web
npm run dev --prefix frontend/mobile
```

Open the desktop console at `http://localhost:5173/` and the floor PWA at
`http://localhost:5174/m/`. Both Vite servers send `/api` requests to the Quarkus application on
port 8080 and send `/auth` requests to the Keycloak Dev Services container on port 8180. No
production nginx stack or extra frontend environment variable is needed for this development path.

## Deploy the free tier

The production path builds the public source and starts four containers: Karyo, PostgreSQL,
Keycloak, and nginx. Commercial engine implementations are not in that image.

1. Copy the public environment template and restrict its permissions:

   ```bash
   cp scripts/.env.prod.example scripts/.env.prod
   chmod 600 scripts/.env.prod
   ```

2. Edit `scripts/.env.prod` and generate independent high-entropy values:

   - `DB_PASSWORD`, `POSTGRES_PASSWORD`, and `KC_DB_PASSWORD` configure PostgreSQL. Follow the
     comments in the template and do not reuse them for identity credentials.
   - `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` are externally supplied,
     one-time credentials for a fresh Keycloak database. They are not a permanent Karyo identity,
     and they belong out of the file once the bootstrap administrator is retired: the deploy
     script requires them only for a fresh database or `--reset-db`.
   - `KEYCLOAK_ADMIN_CLIENT_SECRET` is a distinct secret of at least 32 characters for the
     permanent, least-privilege `karyo-admin` user-management service identity.
   - `OIDC_SECRET` is another distinct secret of at least 32 characters. It belongs to the separate
     `karyo-backend` authentication-audit identity, which has `view-events` and `view-users` only.

   Do not derive either service secret from the one-time bootstrap password, and do not share the
   two service secrets with each other.

   Set the public URL values to the deployment's exact origin. These values must match
   the URL you will open: the production realm binds every browser callback to
   `KARYO_PUBLIC_ORIGIN`.

   For a local rootless-Podman smoke stack that cannot bind port 80:

   ```dotenv
   NGINX_HTTP_PORT=8088
   KARYO_PUBLIC_ORIGIN=http://localhost:8088
   KARYO_DOMAIN=localhost
   KC_HOSTNAME=http://localhost:8088/auth
   ```

   Then open `http://localhost:8088`. For an HTTPS deployment at `https://wms.example.com`:

   ```dotenv
   KARYO_PUBLIC_ORIGIN=https://wms.example.com
   KARYO_DOMAIN=wms.example.com
   KC_HOSTNAME=https://wms.example.com/auth
   ```

   The public production realm keeps `KARYO_PUBLIC_ORIGIN` as an import placeholder. On the first
   Keycloak import, it is expanded into four exact callbacks: the desktop root `/`, the floor PWA
   at `/m/`, the floor PWA's silent sign-in callback `/m/silent-check-sso.html`, and the desktop
   console's own silent sign-in callback `/silent-check-sso.html`. Each interface serves its own
   silent sign-in document, so neither sits inside the other's service-worker scope.

   The deploy script requires a canonical credential-free origin and rejects `KARYO_DOMAIN`,
   `KC_HOSTNAME`, or an absolute `KEYCLOAK_URL` that describes another host. Set the value before
   the first deployment. If an existing installation moves to another origin, update the
   `karyo-web` client callbacks and web origin in Keycloak before the restart; changing the
   environment does not re-import an existing realm.

3. Build and start the stack:

   ```bash
   ./scripts/deploy-server.sh
   ```

4. Sign in to the Keycloak administration console's **master** realm with the one-time bootstrap
   credentials. Select the `karyo` realm, create the first named application administrator, and
   assign only the `ADMIN` realm role. Every application user must also have these attributes:

   - `client_id`: the numeric ID of an existing Karyo goods owner. Use `0` only for a system
     administrator; warehouse and goods-owner users should use their operational goods-owner ID.
   - `tenant_code`: the code of the same goods owner, such as `SYS` for client `0`.
   - `principal_kind`: `ops` for operating-company staff who work across goods owners, or `owner`
     for a goods-owner principal restricted to its own `client_id`.
   - `warehouse_id`: optional, but set it when the user's work should carry a warehouse identity.

   Do not omit the first three attributes. A missing `client_id` resolves to system client `0`, and
   a missing or unrecognized `principal_kind` resolves to the restricted `owner` behavior, which
   silently gives the user the wrong operating scope.

   On the user's **Credentials** tab, generate a strong temporary password and require an update at
   first sign-in. Deliver that one-time user credential through a secure channel. If the deployment
   uses an external identity provider, link or invite the account and verify a complete sign-in.

5. In a separate browser session, sign in to Karyo as the new administrator and complete the
   mandatory password change. Return to the master-realm console and delete the temporary
   bootstrap user. Verify that its credentials no longer authenticate, then delete the
   `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` lines from
   `scripts/.env.prod`. Until those lines are removed, later deploys still print the bootstrap
   first-login instructions. After removal they point at the existing application administrator.
   Bootstrap retirement is mandatory, not an optional hardening step.

6. Use Karyo's **Users** page to create a test user with username, email, first and last name,
   password, realm roles, and optional warehouse ID. The form also requires an explicit goods
   owner, chosen from the active owners, and a tenant authority (goods owner or operations).
   Neither is inherited from the acting administrator: an omitted goods owner would provision the
   account under system client `0`. A goods-owner administrator may only create further
   goods-owner principals; operations authority is refused server-side. Then
   deactivate and reactivate the user. This positive proof verifies `karyo-admin` after bootstrap
   retirement. Verify the negative boundary too: `karyo-admin` must
   not read realm events, manage clients, manage the realm, or administer the master realm. Its
   only direct `realm-management` roles in the `karyo` realm are `manage-users` and `view-realm`.

7. Verify that a new sign-in appears in Karyo's audit journal. Audit access belongs to the separate
   `karyo-backend` service account; never grant event-reading roles to the human administrator or
   `karyo-admin`.

A fresh or `--reset-db` database requires newly supplied one-time bootstrap credentials, the same
provisioning and verification flow, and mandatory retirement. Changing those environment values
cannot reset an existing Keycloak database. Never retain or recreate a broad bootstrap
administrator from a permanent identity. The production realm contains no predefined human users
or shared human-user passwords.

For environment variables, health checks, upgrades, and Podman notes, read the
[deployment guide](../../DEPLOY.md). The
[cloud deployment guide](cloud-deployment-guide.md) covers an HTTPS tunnel deployment.

## Develop an extension

Karyo exposes Apache-2.0 contracts in the public `karyo-*-api` Gradle modules. An extension
compiles against an API contract and must not compile against a `-core` implementation. The public
repository is the extension development and test boundary; it does not include commercial source
or private build inputs.

The inventory example is the smallest executable starting point:

- [`StockSelectionFilter`](../../services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/spi/StockSelectionFilter.kt)
  is the public SPI contract.
- [`karyo-inventory-ext-example/build.gradle.kts`](../../services/inventory-service/karyo-inventory-ext-example/build.gradle.kts)
  declares the API and CDI dependencies as `compileOnly`.
- [`HeldLotStockFilter.kt`](../../services/inventory-service/karyo-inventory-ext-example/src/main/kotlin/com/karyo/inventory/ext/example/HeldLotStockFilter.kt)
  excludes synthetic held lots through a batched, owner-scoped API lookup while preserving candidate order.
  The older `HazmatStockFilter` is an inactive CDI alternative even in the augmented example build;
  its retained pass-through implementation is not hazmat enforcement.

Build the example with the wrapper:

```bash
./gradlew :services:inventory-service:karyo-inventory-ext-example:build
```

Its JAR is written under
`services/inventory-service/karyo-inventory-ext-example/build/libs/`. The example is deliberately
not included in the default `karyo-app`. Enable it during augmentation with
`-PkaryoInventoryExample=true`, not by copying a JAR beside a running process. The
[implementer cookbook](implementer-guide.md#extend-the-free-application) provides the installation,
live-registry and real reservation proof, including an unextended-image negative control.

For a real extension:

1. Select an SPI or event type from a public `karyo-*-api` module.
2. Keep the API and Jakarta annotation dependencies `compileOnly`, as the example does.
3. Implement the contract as a CDI bean and test both its business rule and its fallback behavior.
4. Add `META-INF/beans.xml` to the extension JAR so CDI discovery is explicit.
5. Build and test the extension against the public API modules without adding a dependency on a
   commercial `-core` module.

A free implementer may build an augmented Apache-2.0 application without vendor approval.
Commercial combined-image delivery is a separate vendor-managed path; confirm its availability
before relying on it. See [Commercial engines](../../PAID-MODULES.md) for the design, evidence
limits and contact route. Neither path supports runtime JAR upload; commercial source and private
build inputs are not distributed.

The [extensibility architecture](../architecture/extensibility-architecture.md) explains SPI, CDI
event, strategy-property, and webhook extension mechanisms. The
[stock-selection specification](../functional/stock-selection.md) describes where this example's
filter participates in allocation.

## Documentation map

All links in this section are part of the public repository:

- Connected warehouse setup and executable extension: [implementer guide](implementer-guide.md)
- Functional behavior: [stock selection](../functional/stock-selection.md),
  [location finding](../functional/location-finder.md), and [picking](../functional/picking.md)
- HTTP conventions and RFC 7807 errors: [API standards](../architecture/api-standards.md)
- Integration events, signatures, and retries: [webhook event catalog](../integration/webhook-event-catalog.md)
- Deployment: [production deployment](../../DEPLOY.md) and
  [cloud deployment](cloud-deployment-guide.md)
- [Current architecture](https://github.com/voyagerforge-dev/karyo/wiki/Technical-Architecture-Overview): runtime, requests and module boundaries
