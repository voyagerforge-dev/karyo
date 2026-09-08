# Karyo WMS - Deployment Guide

Covers local and cloud deployment of the full Karyo WMS stack using Docker (recommended) or Podman (fallback).

## Prerequisites

Install the following before deploying:

- **Java 21+** (JDK, not JRE) -- `javac` must be available
- **Node.js 22+** -- required for the frontend build (`frontend/web/package.json` declares
  `engines.node >= 22` because `@pact-foundation/pact` does, and the nginx image's desktop and
  floor-PWA builder stages both pin Node 22 to match) and for
  `--validate-env`, which uses Node's WHATWG URL parser to validate public origins. The deploy
  script rejects an older major in stage 1, before the host `npm ci`, because npm only warns on
  an engine mismatch. The end-to-end wrapper `scripts/run-e2e.sh` additionally requires
  **Node 22.6+** for the `--experimental-strip-types` target resolver, and checks that before
  it uses it
- **Python 3** -- required for deployment configuration validation
- **Docker** (with compose plugin, recommended) OR **Podman** + `podman-compose` (fallback)
- **cloudflared** -- Cloudflare Tunnel client for public HTTPS access
- **Git**

The deploy script verifies all prerequisites at startup and reports what is missing.

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/voyagerforge-dev/karyo.git
cd karyo

# 2. Create environment file from template (mode 0600)
install -m 0600 scripts/.env.prod.example scripts/.env.prod

# 3. Edit .env.prod -- replace every CHANGE_ME value and set the deployment URLs
#    KARYO_PUBLIC_ORIGIN is the exact browser origin, for example https://wms.example.com

# 4. Deploy
./scripts/deploy-server.sh

# 5. Access the application
#    Local:  http://localhost
#    Public: https://<your-domain> (requires cloudflared)
```

The production realm imports no human users or passwords. The first application administrator is
created after deployment with the externally supplied Keycloak bootstrap-admin credentials, as
described below. The development realm remains the deliberate demo path with its demo users.

## Environment Configuration

The deploy script reads configuration from `scripts/.env.prod`. Create it mode 0600 from the example file (`install -m 0600 scripts/.env.prod.example scripts/.env.prod`) and edit it before deploying. The script creates a missing file that way and keeps the source file at mode 0600. After validation, it renders gitignored, mode-0600 service files (`.env.prod.postgresql`, `.env.prod.keycloak`, `.env.prod.app`, and `.env.prod.nginx`) by creating each temporary file with those permissions before writing secrets. Compose mounts only the variables each container needs, so database and bootstrap credentials never enter nginx or the application container. Edit only `.env.prod`; every deploy refreshes the generated files.

### Required Variables

These must be set to real values (not `CHANGE_ME`). The deploy script validates them before starting containers.

| Variable | Purpose |
|----------|---------|
| `DB_USERNAME` | PostgreSQL role used by the Karyo application; must equal `POSTGRES_USER` and `KC_DB_USERNAME` |
| `DB_PASSWORD` | Shared PostgreSQL role password, at least 16 characters; must equal `POSTGRES_PASSWORD` and `KC_DB_PASSWORD` |
| `POSTGRES_USER` | PostgreSQL role provisioned by the container; must equal `DB_USERNAME` and `KC_DB_USERNAME` |
| `POSTGRES_PASSWORD` | Password used when provisioning the shared PostgreSQL role; must equal the application and Keycloak database passwords |
| `KC_DB_USERNAME` | Keycloak database role; must equal `DB_USERNAME` and `POSTGRES_USER` |
| `KC_DB_PASSWORD` | Keycloak database password; must equal `DB_PASSWORD` and `POSTGRES_PASSWORD` |
| `KC_BOOTSTRAP_ADMIN_USERNAME` | Externally chosen Keycloak bootstrap-admin username. Required only for a fresh Keycloak database or `--reset-db`; remove it after bootstrap retirement |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | Externally generated bootstrap-admin password, at least 16 characters, non-dictionary, non-predictable, and non-repeating. Same lifetime as the username above |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Distinct non-dictionary, non-predictable, non-repeating secret for the permanent `karyo-admin` service account, at least 32 characters |
| `OIDC_SECRET` | Distinct non-dictionary, non-predictable, non-repeating secret substituted into the `karyo-backend` confidential client during realm import, at least 32 characters |
| `KARYO_PUBLIC_ORIGIN` | Exact browser-canonical origin with lowercase ASCII/punycode host, no default port, path, trailing slash, credentials, or wildcard (e.g., `https://wms.yourcompany.com`) |
| `KARYO_DOMAIN` | Hostname portion of `KARYO_PUBLIC_ORIGIN` (e.g., `wms.yourcompany.com`) |
| `KC_HOSTNAME` | Keycloak public URL including the `/auth` path (e.g., `https://wms.yourcompany.com/auth`) |

The deploy script rejects missing placeholders, duplicate required assignments, Compose-style
quoting, comments, interpolation or whitespace in required values, mismatched shared database
credentials, reused or weak service secrets, trivial bootstrap passwords, username-derived secrets,
and non-canonical public origins before it starts any production container. Existing database
role passwords are not grandfathered and are not rotated automatically. Run
`./scripts/deploy-server.sh --validate-env scripts/.env.prod` to check the file without building or
starting containers. The origin must match the browser's `URL.origin`
serialization, including omission of `:80` for HTTP and `:443` for HTTPS. The production realm
permits only the exact desktop callback `/`, desktop silent SSO callback `/silent-check-sso.html`,
floor callback `/m/`, and floor silent SSO callback `/m/silent-check-sso.html` beneath
`KARYO_PUBLIC_ORIGIN`.

### Rotate the PostgreSQL Role Password

PostgreSQL applies `POSTGRES_PASSWORD` only while initializing a fresh data directory. Changing
`DB_PASSWORD`, `POSTGRES_PASSWORD`, and `KC_DB_PASSWORD` in `scripts/.env.prod` does not update the
persisted role. Deploy does not grandfather a previously accepted short or weak password, and it
does not run `ALTER ROLE` for you. A password that fails the credential policy is rejected before
any container is started, naming PostgreSQL role `POSTGRES_USER` and this procedure. If the three
environment values are a valid new secret that the live role does not yet accept, deploy starts
PostgreSQL, then halts before Keycloak or karyo-app with a message that the environment was updated
but the role was not rotated.

To rotate the live password:

1. Keep the current working password in `scripts/.env.prod` so you can still authenticate.
2. Generate a new password that satisfies the credential policy (at least 16 characters, not a
   dictionary, predictable, repeated, or username-derived value).
3. Read both values at hidden prompts and validate the exact candidate before changing the role.
   Substitute the actual `POSTGRES_USER` / `DB_USERNAME` / `KC_DB_USERNAME` value if it is not
   `karyo`:

   ```bash
   DB_ROLE=karyo
   read -rsp 'Current PostgreSQL role password: ' PGPASSWORD
   echo
   read -rsp 'New PostgreSQL role password: ' ROTATED_DB_PASSWORD
   echo
   export PGPASSWORD ROTATED_DB_PASSWORD
   printf '%s' "$ROTATED_DB_PASSWORD" |
     python3 scripts/validate_credential.py --min-length 16 --username "$DB_ROLE" --env-literal
   ```

4. Apply that validated value through a quoted `psql` stdin program. Docker copies the two exported
   values by environment-variable name, so neither password appears in process arguments, the SQL
   program, or shell history:

   ```bash
   # Keep COMPOSE_PROJECT_NAME set to the deployment selected below.
   POSTGRES_CONTAINER=$(docker ps -q \
     --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-karyo-prod}" \
     --filter "label=com.docker.compose.service=postgresql")
   test -n "$POSTGRES_CONTAINER" || { echo 'No PostgreSQL container in the selected project'; exit 1; }
   docker exec -i --env PGPASSWORD --env ROTATED_DB_PASSWORD "$POSTGRES_CONTAINER" \
     psql -h 127.0.0.1 -U "$DB_ROLE" -d postgres -v ON_ERROR_STOP=1 <<'SQL'
   \getenv rotated_password ROTATED_DB_PASSWORD
   SELECT format('ALTER ROLE %I WITH PASSWORD %L', current_user, :'rotated_password') \gexec
   SQL
   ROTATION_STATUS=$?
   unset PGPASSWORD ROTATED_DB_PASSWORD
   test "$ROTATION_STATUS" -eq 0
   ```

   Use `podman` in place of `docker` when that is the container runtime. Both runtimes accept an
   environment-variable name without its value for this command.
5. Set all three environment values in `scripts/.env.prod` to the new password:
   `DB_PASSWORD`, `POSTGRES_PASSWORD`, and `KC_DB_PASSWORD`.
6. Restart Keycloak and karyo-app together with `./scripts/deploy-server.sh` or
   `./scripts/deploy-server.sh --quick` so both authenticate with the rotated role. Do not restart
   only one of them.
7. Confirm `/q/health/ready` and `/auth/realms/karyo` succeed.

If you update the three environment values before `ALTER ROLE`, the next deploy will stop after
PostgreSQL is up and print this procedure. Run the `ALTER ROLE` with the new password, then re-run
deploy. There is no supported path that leaves Keycloak or karyo-app running against a password the
live role does not have.

### Provision the First Application Administrator

Keycloak creates the bootstrap admin from `KC_BOOTSTRAP_ADMIN_USERNAME` and
`KC_BOOTSTRAP_ADMIN_PASSWORD` only when its database is fresh. Those credentials are supplied in
`scripts/.env.prod`; they are not part of either Karyo realm export. The deploy script validates
any supplied bootstrap values before starting containers. After PostgreSQL is healthy, it checks
the actual Keycloak schema and requires both values when no initialized master realm exists or
when `--reset-db` is used. After bootstrap retirement both assignments must be removed from the
source and rendered Keycloak environment files.

After the first deployment:

1. Open `<KARYO_PUBLIC_ORIGIN>/auth/admin/` and sign in to the **master** realm with the
   `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` values.
2. Select the **karyo** realm, open **Users**, and create the first application administrator.
3. Set the user attributes `client_id=0`, `principal_kind=ops`, `tenant_code=SYS`, and the
   deployment's warehouse identifier (for example `warehouse_id=WH-001`).
4. On **Credentials**, set an independently generated strong temporary password, leave
   **Temporary** on, and deliver it through a secure channel.
5. On **Role mapping**, assign the `ADMIN` realm role.
6. In a separate browser session, sign in to Karyo with the new application account and complete
   the mandatory password change. Sign out, prove the temporary password no longer works, then
   sign back in with the replacement password.
7. Return to the admin console's **master** realm and delete the temporary bootstrap user. Verify
   that its credentials no longer authenticate, then remove both bootstrap assignments without
   creating another secret-bearing file:

   ```bash
   python3 - <<'PY'
   import os
   from pathlib import Path

   path = Path("scripts/.env.prod")
   retired = {"KC_BOOTSTRAP_ADMIN_USERNAME", "KC_BOOTSTRAP_ADMIN_PASSWORD"}
   with path.open("r+", encoding="utf-8") as env_file:
       lines = env_file.readlines()
       env_file.seek(0)
       env_file.writelines(
           line for line in lines if line.partition("=")[0] not in retired
       )
       env_file.truncate()
   os.chmod(path, 0o600)
   PY
   ./scripts/deploy-server.sh --validate-env scripts/.env.prod
   python3 scripts/render_compose_env.py scripts/.env.prod >/dev/null
   ```

8. In Karyo, create a test user on the **Users** page and explicitly select an active goods owner
   such as `ACME`. Sign in as that user and verify its work is attributed to the selected owner,
   then deactivate and reactivate it. Goods-owner administrators can select only their own owner;
   operations administrators can manage users across the owners visible on this page.

Karyo user management uses the separate `karyo-admin` confidential client. Its service account has
only `manage-users` and `view-realm` in the `karyo` realm. Login auditing continues to use the
separate `karyo-backend` client with `view-events` and `view-users`; neither service identity has
master-realm administrator access. Rotate `KEYCLOAK_ADMIN_CLIENT_SECRET` by changing the
`karyo-admin` client secret in Keycloak and the environment value together.

For later realm or client maintenance, do not keep a master-realm administrator. Use the isolated,
loopback-only creation, verification, and retirement procedure in
[Upgrade an Existing Keycloak Database](#upgrade-an-existing-keycloak-database). Keep every
application, reverse proxy, and other ordinary Keycloak route stopped until the temporary
administrator is retired.

Changing bootstrap environment variables does not reset an account in an existing Keycloak
database. After the bootstrap user is deleted, those values no longer authenticate and must not
remain in `scripts/.env.prod` or its rendered Keycloak environment. A `--reset-db` deployment
destroys Keycloak state and requires newly generated one-time bootstrap credentials, so repeat the
first-user, password-rotation, deletion, and credential-file cleanup steps after every reset.

### Upgrade an Existing Keycloak Database

Keycloak skips realm import when the `karyo` realm already exists. A normal redeploy cannot remove
legacy demo users or wildcard callbacks.

**Read this before you start: the upgrade may require rotating `OIDC_SECRET`.** This release
introduces the credential policy in `scripts/validate_credential.py`, and both the migration and
every deploy -- including `--quick` -- enforce it. A `karyo-backend` secret that predates the
policy, in particular the placeholder `dev-backend-secret`, will be rejected. Deployments in that
position must mint a new secret as part of this upgrade; the migration then writes it to the
`karyo-backend` client, so it is a genuine secret rotation, not a config edit. Decide this now
rather than discovering it at step 5 with the stack half-migrated.

Before deploying this release against an existing database:

1. Isolate the identity service, then take a restorable backup of the Keycloak database and prove
   it restores, BEFORE anything else. Step 6 rotates the `karyo-backend` client secret and retires
   accounts; a run that fails partway is only recoverable from a backup you already know is good.
   On a multi-node deployment, first stop every application, proxy, load-balancer route, and
   Keycloak node.

   ```bash
   python3 scripts/render_compose_env.py scripts/.env.prod >/dev/null
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     stop karyo-app nginx keycloak
   test -z "$(docker compose -f infrastructure/docker/docker-compose.prod.yml \
     ps --status running -q karyo-app nginx keycloak)"
   install -m 0600 /dev/null keycloak-pre-migration.dump
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'pg_dump -U "$POSTGRES_USER" -Fc keycloak' > keycloak-pre-migration.dump
   test "$(stat -c '%a' keycloak-pre-migration.dump)" = 600

   # Prove the dump restores before trusting it. Every command here must exit 0.
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "CREATE DATABASE keycloak_restore_check"'
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'pg_restore -U "$POSTGRES_USER" -d keycloak_restore_check' < keycloak-pre-migration.dump
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d keycloak_restore_check \
       -c "SELECT count(*) FROM user_entity"'
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'psql -U "$POSTGRES_USER" -c "DROP DATABASE keycloak_restore_check"'
   ```

   Keep `keycloak-pre-migration.dump` until step 8 has verified the deployment end to end. It
   contains password hashes and client secrets: store it as a secret and delete it afterwards.

2. With the isolation from step 1 still in place, create a temporary master-realm maintenance
   administrator while Keycloak is stopped, then expose only its loopback maintenance port. The
   password is read into the shell and passed by environment variable, never as a command-line
   argument.

   ```bash
   export KARYO_KEYCLOAK_MAINTENANCE_PORT="$(
     grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' scripts/.env.prod | cut -d= -f2-
   )"
   : "${KARYO_KEYCLOAK_MAINTENANCE_PORT:=8181}"
   export KARYO_KEYCLOAK_MAINTENANCE_URL="http://127.0.0.1:${KARYO_KEYCLOAK_MAINTENANCE_PORT}/auth"
   read -rp 'Temporary Keycloak maintenance username: ' KARYO_KEYCLOAK_MAINTENANCE_USERNAME
   read -rsp 'Temporary Keycloak maintenance password: ' KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   echo
   export KARYO_KEYCLOAK_MAINTENANCE_USERNAME KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   ./scripts/migrate_keycloak_realm.py --validate-maintenance-credentials

   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     stop karyo-app nginx keycloak
   test -z "$(docker compose -f infrastructure/docker/docker-compose.prod.yml \
     ps --status running -q karyo-app nginx keycloak)"
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     run --rm --no-deps \
     -e KARYO_KEYCLOAK_MAINTENANCE_USERNAME \
     -e KARYO_KEYCLOAK_MAINTENANCE_PASSWORD \
     keycloak bootstrap-admin user \
     --username:env KARYO_KEYCLOAK_MAINTENANCE_USERNAME \
     --password:env KARYO_KEYCLOAK_MAINTENANCE_PASSWORD --no-prompt
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     -f infrastructure/docker/docker-compose.maintenance.yml \
     up -d --no-deps --force-recreate keycloak
   for _ in $(seq 1 150); do
     curl --noproxy '*' --fail --silent \
       "${KARYO_KEYCLOAK_MAINTENANCE_URL}/realms/master" >/dev/null && break
     sleep 2
   done
   curl --noproxy '*' --fail --silent \
     "${KARYO_KEYCLOAK_MAINTENANCE_URL}/realms/master" >/dev/null
   test -z "$(docker compose -f infrastructure/docker/docker-compose.prod.yml \
     ps --status running -q karyo-app nginx)"
   ./scripts/migrate_keycloak_realm.py --verify-maintenance-admin
   ```

   Do not continue unless the final three commands succeed. The migration accepts only a literal
   loopback HTTP `/auth` URL with an explicit port; it cannot send maintenance credentials through
   nginx, a public origin, or a redirect.

3. Settle both secrets, and make sure each satisfies the credential policy.

   - `KEYCLOAK_ADMIN_CLIENT_SECRET`: generate a distinct securely-random secret of at least 32
     characters and put it in `scripts/.env.prod`. This client is new, so this value is always new.
   - `OIDC_SECRET`: must ALSO satisfy the policy. Check the current value:

     ```bash
     printf '%s' "$(grep '^OIDC_SECRET=' scripts/.env.prod | cut -d= -f2-)" |
       python3 scripts/validate_credential.py --min-length 32 --username karyo-backend --env-literal
     ```

     Exit 0 means it qualifies and you may keep it. Any other exit means it does not: generate a
     replacement (`python3 -c 'import secrets; print(secrets.token_urlsafe(36))'`), put it in
     `.env.prod`, and treat this upgrade as a `karyo-backend` secret rotation. The migration writes
     whichever value you supply to the `karyo-backend` client, so the realm and `.env.prod` end up
     in step, and there is no way to keep a pre-policy secret: the deploy in step 7 rejects it too.

   Remove retired `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` assignments.

4. Export the deployment-bound origin and the two service secrets already settled in step 3:

   ```bash
   export KARYO_PUBLIC_ORIGIN='https://wms.yourcompany.com'
   export KEYCLOAK_ADMIN_CLIENT_SECRET='<same-distinct-secret-as-.env.prod>'
   export OIDC_SECRET='<same-backend-secret-as-.env.prod>'
   ```

5. Leave `karyo-app` and nginx stopped and run
   `./scripts/migrate_keycloak_realm.py --apply --verified-backup --isolated-maintenance`. The
   backup flag attests that step 1's restore check succeeded, and the isolation flag attests that
   step 2 stopped every ordinary route before fingerprinting; without either flag the migration
   refuses before contacting Keycloak. Before any mutation the migration proves, for every
   candidate, both that the complete fixture
   fingerprint matches AND that the account still authenticates with its seeded non-temporary
   credential. The complete fingerprint includes the exact attributes, direct realm roles, group
   membership, required actions, and identity-provider state. An identity whose profile changed, whose password
   was rotated, or which cannot be conclusively probed makes the migration refuse the whole run
   without touching the realm. On a refusal, move the affected operator to a separately named
   account or resolve the ambiguous identity manually, then rerun.

6. The default run irreversibly deletes only the identities proven in step 5. It revokes all realm
   sessions plus each candidate's sessions, then waits out the realm's longest configured
   access-token lifespan while `karyo-app`, nginx, and every other ordinary route remain stopped,
   so a bearer minted immediately before maintenance expires before the application returns. The
   production realm's current wait is 900
   seconds. That wait is an outage, so the migration resolves it and prints
   `Planned access-token drain: <n> seconds (longest lifespan: <source>)` **before** it revokes
   anything or deletes any account -- you learn how long the stack stays down while you can still
   walk away, and the message names whichever realm setting or client attribute set the length.
   A drain longer than 3600 seconds refuses the run outright, before any mutation, naming the
   same setting: lower that realm lifespan or that client's `access.token.lifespan` in Keycloak
   and rerun, or accept the outage explicitly with `--max-drain-seconds <n>`.
   The migration also binds the four exact `karyo-web` callbacks, disables the legacy
   backend password grant, updates the `karyo-backend` client secret to `OIDC_SECRET`, removes
   interactive credentials and federated identities from both service accounts, and configures
   their least-privilege authority. Both client-credentials grants are verified before deletion and
   again after the token drain. Deletion is recoverable only from the verified step-1 backup. A
   successful rerun deletes zero fixtures but repeats normalization, session revocation, and the
   token drain.

7. Retire the authenticated temporary administrator while Keycloak is still reachable only on
   loopback. The command deletes the authenticated account from the master realm and proves the same
   username and password can no longer obtain a token:

   ```bash
   ./scripts/migrate_keycloak_realm.py --retire-maintenance-admin
   unset KARYO_KEYCLOAK_MAINTENANCE_USERNAME KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   ```

   Then take the loopback maintenance port back down. This step is mandatory: the production
   compose file publishes no Keycloak port, so recreating Keycloak without the maintenance
   override restores the posture where only nginx can reach the identity service. The check reads
   the host listening sockets rather than any container-runtime output: the port forwarder binds
   the socket as soon as the container starts, so a bound port is detected even before Keycloak
   serves its first request. It takes the port number from `scripts/.env.prod` rather than from
   the environment, so it is safe to re-run from a fresh shell. A value that is present but not a
   number stops the step rather than silently widening the socket filter; an absent value falls
   back to the documented default of 8181, which is the same port the maintenance override and
   step 2 use, so the probe still targets the port you brought up. It names the port it
   probed, so a value that does not match the one you brought up is visible rather than silently
   accepted. Only the success branch clears the maintenance variables, so a stop leaves
   everything you need to retry. Proceed only when it reports the expected port closed.

   ```bash
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     up -d --no-deps --force-recreate keycloak
   maintenance_port="$(
     grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' scripts/.env.prod | cut -d= -f2-
   )"
   : "${maintenance_port:=8181}"
   if ! printf '%s' "$maintenance_port" | grep -qE '^[0-9]+$'; then
     echo "STOP: KARYO_KEYCLOAK_MAINTENANCE_PORT is not a port number: '$maintenance_port'" >&2
     false
   elif ! bound="$(ss -tlnH "sport = :$maintenance_port")"; then
     echo 'STOP: could not query the host listening sockets' >&2
     false
   elif [ -n "$bound" ]; then
     printf '%s\n' "$bound" >&2
     echo "STOP: port $maintenance_port is still bound on this host" >&2
     false
   else
     echo "Port $maintenance_port is closed; Keycloak is reachable only through nginx"
     unset KARYO_KEYCLOAK_MAINTENANCE_URL KARYO_KEYCLOAK_MAINTENANCE_PORT maintenance_port bound
   fi
   ```

8. Run `./scripts/deploy-server.sh --validate-env scripts/.env.prod`, then run the full
   `./scripts/deploy-server.sh` deployment without `--quick`. This rebuild is required because the
   application now uses the `karyo-admin` client-credentials flow. Verify login auditing plus user
   creation, deactivation, reactivation, role assignment, and password reset through Karyo. Then
   clear the remaining migration values:

   ```bash
   unset KARYO_PUBLIC_ORIGIN KEYCLOAK_ADMIN_CLIENT_SECRET OIDC_SECRET
   ```

#### Roll Back a Failed Realm Migration

A refusal needs no rollback: the migration mutates nothing when a preflight check fails, so read
the message, fix the named condition, and rerun. Once any Keycloak write is attempted, even a lost
response makes the result ambiguous: the failure starts with `REALM MIGRATION MAY BE PARTIALLY
APPLIED`, stops the deployment, and names this rollback procedure. Roll back whenever that message
appears, or when Karyo fails to start after an otherwise successful migration.

1. Stop the application, public proxy, and Keycloak so nothing writes while the database is
   replaced and no ordinary route can reach the restored identity service:

   ```bash
   docker compose -f infrastructure/docker/docker-compose.prod.yml stop karyo-app nginx keycloak
   test -z "$(docker compose -f infrastructure/docker/docker-compose.prod.yml \
     ps --status running -q karyo-app nginx keycloak)"
   ```

2. Restore the step-1 dump over the Keycloak database:

   ```bash
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "DROP DATABASE keycloak"'
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
       -c "CREATE DATABASE keycloak OWNER \"$POSTGRES_USER\""'
   docker compose -f infrastructure/docker/docker-compose.prod.yml exec -T postgresql \
     sh -c 'pg_restore -U "$POSTGRES_USER" -d keycloak' < keycloak-pre-migration.dump
   ```

3. Recreate and verify a temporary master-realm maintenance administrator. The step-1 dump was
   taken before that account existed, so the restore removed it. Keycloak remains stopped while the
   dedicated command writes the account, and only the loopback maintenance port is published when
   Keycloak restarts:

   ```bash
   export KARYO_KEYCLOAK_MAINTENANCE_PORT="$(
     grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' scripts/.env.prod | cut -d= -f2-
   )"
   : "${KARYO_KEYCLOAK_MAINTENANCE_PORT:=8181}"
   export KARYO_KEYCLOAK_MAINTENANCE_URL="http://127.0.0.1:${KARYO_KEYCLOAK_MAINTENANCE_PORT}/auth"
   read -rp 'Temporary Keycloak maintenance username: ' KARYO_KEYCLOAK_MAINTENANCE_USERNAME
   read -rsp 'Temporary Keycloak maintenance password: ' KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   echo
   export KARYO_KEYCLOAK_MAINTENANCE_USERNAME KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   ./scripts/migrate_keycloak_realm.py --validate-maintenance-credentials

   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     run --rm --no-deps \
     -e KARYO_KEYCLOAK_MAINTENANCE_USERNAME \
     -e KARYO_KEYCLOAK_MAINTENANCE_PASSWORD \
     keycloak bootstrap-admin user \
     --username:env KARYO_KEYCLOAK_MAINTENANCE_USERNAME \
     --password:env KARYO_KEYCLOAK_MAINTENANCE_PASSWORD --no-prompt
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     -f infrastructure/docker/docker-compose.maintenance.yml \
     up -d --no-deps --force-recreate keycloak
   for _ in $(seq 1 150); do
     curl --noproxy '*' --fail --silent \
       "${KARYO_KEYCLOAK_MAINTENANCE_URL}/realms/master" >/dev/null && break
     sleep 2
   done
   curl --noproxy '*' --fail --silent \
     "${KARYO_KEYCLOAK_MAINTENANCE_URL}/realms/master" >/dev/null
   test -z "$(docker compose -f infrastructure/docker/docker-compose.prod.yml \
     ps --status running -q karyo-app nginx)"
   ./scripts/migrate_keycloak_realm.py --verify-maintenance-admin
   ```

4. Keep karyo-app and nginx stopped. Generate a new `karyo-backend` secret for the restored realm,
   enter it at the hidden prompt, and validate it before any realm mutation:

   ```bash
   read -rsp 'New restored karyo-backend client secret: ' OIDC_SECRET
   echo
   export OIDC_SECRET
   printf '%s' "$OIDC_SECRET" |
     python3 scripts/validate_credential.py --min-length 32 --username karyo-backend --env-literal
   ```

   Put this exact value in the `OIDC_SECRET` assignment in mode-0600 `scripts/.env.prod` without
   passing it on a command line. Do not reuse either the pre-migration secret or the failed
   migration's secret.

5. Select a distinct policy-compliant `karyo-admin` secret and put it in the
   `KEYCLOAK_ADMIN_CLIENT_SECRET` assignment in mode-0600 `scripts/.env.prod`:

   ```bash
   read -rsp 'Restored karyo-admin client secret: ' KEYCLOAK_ADMIN_CLIENT_SECRET
   echo
   export KEYCLOAK_ADMIN_CLIENT_SECRET
   printf '%s' "$KEYCLOAK_ADMIN_CLIENT_SECRET" |
     python3 scripts/validate_credential.py --min-length 32 --username karyo-admin --env-literal
   ```

6. Export the deployment's exact public origin, then rerun the complete production hardening
   through the loopback Admin API while every public route remains stopped:

   ```bash
   export KARYO_PUBLIC_ORIGIN="$(grep '^KARYO_PUBLIC_ORIGIN=' scripts/.env.prod | cut -d= -f2-)"
   ./scripts/migrate_keycloak_realm.py --apply --verified-backup --isolated-maintenance
   ```

   Do not continue unless it succeeds. This command retires every exact legacy fixture account and
   proves its reusable demo credential no longer works, replaces wildcard callbacks and origins
   with the four exact deployment-bound callbacks, writes the new `OIDC_SECRET` plus the distinct
   `KEYCLOAK_ADMIN_CLIENT_SECRET`, applies least-privilege service roles, verifies both
   client-credentials grants, revokes realm sessions, and waits out the longest access-token
   lifespan. That wait is announced up front and refused past the ceiling exactly as described in
   the migration's step 6. If a restored human identity no longer matches the fixture fingerprint,
   keep the deployment isolated, resolve that identity manually, and rerun the complete hardening.
   Never substitute `--reconcile-service-clients`: that mode intentionally leaves human users and
   `karyo-web` callbacks unchanged.

7. Retire the temporary master-realm administrator before restoring any public route. The command
   deletes the authenticated account and proves its credentials are rejected:

   ```bash
   ./scripts/migrate_keycloak_realm.py --retire-maintenance-admin
   unset KARYO_KEYCLOAK_MAINTENANCE_USERNAME KARYO_KEYCLOAK_MAINTENANCE_PASSWORD
   ```

   Then take the loopback maintenance port back down. This step is mandatory: the production
   compose file publishes no Keycloak port, so recreating Keycloak without the maintenance
   override restores the posture where only nginx can reach the identity service. The check reads
   the host listening sockets rather than any container-runtime output: the port forwarder binds
   the socket as soon as the container starts, so a bound port is detected even before Keycloak
   serves its first request. It takes the port number from `scripts/.env.prod` rather than from
   the environment, so it is safe to re-run from a fresh shell. A value that is present but not a
   number stops the step rather than silently widening the socket filter; an absent value falls
   back to the documented default of 8181, which is the same port the maintenance override and
   step 3 use, so the probe still targets the port you brought up. It names the port it
   probed, so a value that does not match the one you brought up is visible rather than silently
   accepted. Only the success branch clears the maintenance variables, so a stop leaves
   everything you need to retry. Proceed only when it reports the expected port closed.

   ```bash
   docker compose -f infrastructure/docker/docker-compose.prod.yml \
     up -d --no-deps --force-recreate keycloak
   maintenance_port="$(
     grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' scripts/.env.prod | cut -d= -f2-
   )"
   : "${maintenance_port:=8181}"
   if ! printf '%s' "$maintenance_port" | grep -qE '^[0-9]+$'; then
     echo "STOP: KARYO_KEYCLOAK_MAINTENANCE_PORT is not a port number: '$maintenance_port'" >&2
     false
   elif ! bound="$(ss -tlnH "sport = :$maintenance_port")"; then
     echo 'STOP: could not query the host listening sockets' >&2
     false
   elif [ -n "$bound" ]; then
     printf '%s\n' "$bound" >&2
     echo "STOP: port $maintenance_port is still bound on this host" >&2
     false
   else
     echo "Port $maintenance_port is closed; Keycloak is reachable only through nginx"
     unset KARYO_KEYCLOAK_MAINTENANCE_URL KARYO_KEYCLOAK_MAINTENANCE_PORT maintenance_port bound
   fi
   ```

8. Only after step 6 has verified fixture retirement, exact callbacks, the synchronized new backend
   secret, and both service grants, and step 7 has retired the temporary administrator, validate
   the environment and restore public service:

   ```bash
   ./scripts/deploy-server.sh --validate-env scripts/.env.prod
   ./scripts/deploy-server.sh --quick
   ```

   Sign in through the browser, confirm login auditing records events (Admin -> Audit log), then
   create a temporary user through Admin -> Users, reset its password, change a role, and
   deactivate it. If any check fails, the restored deployment is incomplete. Clear every remaining
   credential and migration value exported for rollback:

   ```bash
   unset KARYO_PUBLIC_ORIGIN KEYCLOAK_ADMIN_CLIENT_SECRET OIDC_SECRET
   ```

The migration deletes proven fixture accounts. Only the verified backup recovers a mistaken
retirement.

### Optional Variables

These have working defaults for standard setups. Override only when needed.

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://postgresql:5432/karyo` | JDBC connection URL |
| `OIDC_URL` | `http://keycloak:8080/auth/realms/karyo` | Keycloak OIDC endpoint |
| `NGINX_HTTP_PORT` | `80` | Host port nginx binds to. Set a high port (e.g., `8088`) for rootless Podman that cannot bind 80 |
| `KARYO_KEYCLOAK_MAINTENANCE_PORT` | `8181` | Loopback-only Keycloak port, published only while `docker-compose.maintenance.yml` is layered on top of the production compose file for an isolated maintenance window |

### Resource Limits

Resource limits are optional. Leave them commented out unless host-specific tuning is required.

| Variable | Default | Example larger host | What it controls |
|----------|---------|---------------------|------------------|
| `APP_MEM_LIMIT` | `1536m` | `4g` | karyo-app container memory |
| `JAVA_OPTS` | `-Xms128m -Xmx1024m ...` | `-Xms256m -Xmx3g ...` | JVM flags for the app |
| `GRADLE_OPTS` | `-Xmx512m` | `-Xmx2g` | Gradle build heap |

Flyway handles database schema migrations automatically on app startup (single `karyo` schema). No manual SQL is needed for normal deployments.

## Deploy Script Usage

The deploy script is at `scripts/deploy-server.sh`. Re-running it stops and recreates the selected
stack, so plan an outage and identify its project, ports, images and storage first.

### Select the deployment

The script defaults to Compose project `karyo-prod`, application image `karyo/karyo-app:latest`
and nginx image `karyo/nginx:latest`. For a second, isolated rehearsal on a shared runtime, inspect
existing resources and choose an unused project name, image tags and host port:

```bash
export COMPOSE_PROJECT_NAME=karyo-guide
export NGINX_HTTP_PORT=18088
export KARYO_APP_IMAGE=karyo/karyo-app:guide
export KARYO_NGINX_IMAGE=karyo/nginx:guide
export BASE_URL=http://localhost:18088
```

Export `COMPOSE_PROJECT_NAME`, `KARYO_APP_IMAGE` and `KARYO_NGINX_IMAGE` in the invoking shell;
the deploy script does not load these three controls from `scripts/.env.prod`. Retain them for
every deploy, restart, maintenance and browser-test invocation, including direct Compose commands.
Prepare this clone's own mode-0600 environment file with the matching public origin and hostname
settings. An explicit `NGINX_HTTP_PORT` in that file overrides the exported port, so keep it aligned.

Native Compose project names isolate containers, networks and volumes. Explicit image tags avoid
overwriting another stack's tags; a project name alone does not change images, browser origin or
host port. Health and database-password probes use project/service labels on both Docker Compose
and podman-compose. Deployment does not prune images, including untagged images: cleanup is an
explicit operator action. Never reuse another deployment's identities, storage or credentials.
Keep existing credentials on restart; a fresh-database rehearsal needs unused storage, not a reset
of shared volumes. `BASE_URL` selects the browser-test target, not the deployment origin.

### Full Rebuild (default)

```bash
./scripts/deploy-server.sh
```

Rebuilds Gradle artifacts, both frontends, and container images with `--no-cache`. Preserves the
selected project's database volumes so data survives re-deploys.

### Quick Restart

```bash
./scripts/deploy-server.sh --quick
```

Skips build stages (2-4) and restarts containers only. Use after `.env.prod` changes or config-only updates when code has not changed.

### Reset Database

```bash
./scripts/deploy-server.sh --reset-db
```

Destroys the selected project's database volume and starts fresh. All its PostgreSQL and Keycloak
data will be lost. Use only for an explicitly disposable deployment, never to hide a migration
checksum mismatch on retained data.

### Help

```bash
./scripts/deploy-server.sh --help
```

Shows usage and flag descriptions.

### Combining Flags

Flags can be combined. For example, restart containers with a fresh database:

```bash
./scripts/deploy-server.sh --quick --reset-db
```

## What the Script Does

The deploy script runs 8 stages sequentially:

1. **Check prerequisites** -- Verifies Java (JDK 21+), Node.js (presence *and* major version 22+), container runtime (Docker or Podman), and cloudflared. Checks Podman-specific settings (linger, unprivileged ports) when Podman is detected.
2. **Build backend** -- Runs Gradle `quarkusBuild` for the single `karyo-app` modular monolith.
3. **Build frontend** -- Runs `npm ci && npm run build` in both `frontend/web/` and `frontend/mobile/`.
4. **Build container images** -- Builds 2 container images (karyo-app + nginx) using `Dockerfile.service` and `Dockerfile.nginx`.
5. **Validate environment** -- Creates `scripts/.env.prod` mode 0600 from the template if missing, enforces that mode, then checks that required variables and any supplied bootstrap variables are real values (not `CHANGE_ME` placeholders).
6. **Start infrastructure** -- Tears down containers from the previous run, checks for port conflicts, then brings up PostgreSQL. It authenticates the persisted PostgreSQL role and checks the actual Keycloak schema for an initialized master realm before allowing Keycloak to start, then waits for both services to become healthy.
7. **Start application** -- Starts karyo-app and waits for health before starting nginx, with a separate bounded health wait for each.
8. **Verify stack** -- Sends HTTP requests to `/api/v1/stock-units`, `/api/v1/products`, `/api/v1/locations`, `/api/v1/users` through nginx (all served by karyo-app). HTTP 200, 401, or 403 all count as healthy.

Stages 2-4 are skipped when `--quick` is passed. The `--reset-db` flag adds volume destruction before stage 6.

## Local Deployment

### One-time Setup (Podman only)

These steps are only needed if using Podman instead of Docker. Docker runs as a root daemon and handles these automatically.

Enable linger so rootless containers survive SSH disconnects:

```bash
sudo loginctl enable-linger $(whoami)
```

Allow binding port 80 without root:

```bash
sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80
# Persist across reboots:
echo 'net.ipv4.ip_unprivileged_port_start=80' | sudo tee -a /etc/sysctl.conf
```

Alternatively, skip the sysctl change and run nginx on a high port instead by setting `NGINX_HTTP_PORT=8088` in `scripts/.env.prod` (or as an environment variable when invoking the script). The stack is then reachable at `http://localhost:8088`.

### RAM Budget

For a small deployment host, leave resource-limit variables at their documented defaults and tune
them only after observing the complete stack under representative load.

### Cloudflare Tunnel

To expose the local deployment over HTTPS without opening firewall ports, configure a Cloudflare Tunnel:

1. Install `cloudflared` and authenticate with `cloudflared tunnel login`
2. Create a tunnel: `cloudflared tunnel create karyo`
3. Configure the tunnel to route your domain to `http://localhost:80`
4. Start as a service: `sudo systemctl enable --now cloudflared`

## Cloud Deployment (Docker/Ubuntu)

### Setup

1. Create a VM (e.g., Oracle Cloud A1.Flex with 4 OCPU / 24GB RAM)
2. Install Docker with the compose plugin, Java 21, Node.js 22+, Git, and cloudflared
3. Clone the repository and create `scripts/.env.prod`
4. Uncomment resource limit variables in `.env.prod` for larger allocations:
   ```
   APP_MEM_LIMIT=4g
   GRADLE_OPTS=-Xmx2g -XX:MaxMetaspaceSize=512m
   ```
5. Run `./scripts/deploy-server.sh`
6. Set up Cloudflare Tunnel for HTTPS access

### Build Memory

On cloud machines with more RAM, override `GRADLE_OPTS` in `.env.prod` to speed up Gradle builds:

```
GRADLE_OPTS=-Xmx1g -XX:MaxMetaspaceSize=512m
```

The deploy script exports this value before running Gradle.

## Troubleshooting

### "Port X in use"

The pre-flight check (stage 6, after tearing down the selected project's containers) uses `ss` to
detect processes holding the nginx port (`NGINX_HTTP_PORT`, default 80) or 5432. It reports the
process name and PID. Identify its owner before acting; never kill another deployment's process.
Choose an unused nginx port and matching origin, or resolve the conflict with the host operator.

### "Health check timeout"

The script prints the last 20 lines of container logs when a health check times out. Common causes:

- **PostgreSQL**: Wrong password in `.env.prod`, disk full
- **Keycloak**: Slow startup on first run (imports realm), check database connectivity
- **karyo-app**: Database connection errors, Keycloak unreachable, Flyway migration failure

### "CHANGE_ME validation error"

Edit `scripts/.env.prod` and replace every `CHANGE_ME` value. The script validates all variables
listed in the required table, requires the bootstrap password, administration-client secret, and
OIDC secret to be distinct, rejects weak or known demo credentials, and requires
`KARYO_PUBLIC_ORIGIN` to be one exact browser-canonical HTTP(S) origin. It fails before starting
containers.

### "Flyway checksum mismatch"

Restore the applied migration file's exact released bytes, including comments. Put any schema
correction in a new forward migration; adding one alone cannot fix the checksum of an edited old
file. Do not repair, rebaseline or renumber retained data to conceal a mismatch. The reset command
is only for explicitly disposable data.

### Podman: health check hangs

Known Podman issue with exec-based health checks. Not applicable to Docker. If Keycloak health check hangs beyond 200s, restart the deploy script -- the second run usually succeeds. Consider switching to Docker if this occurs frequently.

### "init-db.sh did not run on re-deploy"

This is expected behavior. The PostgreSQL init script (`init-db.sh`) only runs on first start when the data directory is empty. If you need new database schemas after the initial deployment, either:

- Use `--reset-db` to start fresh
- Run the SQL manually against the running PostgreSQL container

### "Container build fails on ARM64"

Ensure base images support aarch64. All images used in the stack have ARM64 variants:

- `postgres:16-alpine`
- `quay.io/keycloak/keycloak:26.0`
- `eclipse-temurin:21-jre-alpine` (app base)

## Architecture Overview

- **Stack**: 1 Quarkus app (karyo-app modular monolith) + PostgreSQL + Keycloak + nginx reverse proxy
- **Database**: Single PostgreSQL instance with one application schema (`karyo`) + a separate Keycloak database. Internal module events are in-process (no Kafka broker)
- **Volumes**: Storage belongs to the [selected Compose project](#select-the-deployment). Bootstrap decisions come from the initialized Keycloak schema, not a volume name. Use `--reset-db` only for an explicitly disposable project.
- **Networking**: All services communicate on a container network. Nginx reverse proxy handles external traffic on the host port `NGINX_HTTP_PORT` (default 80). Keycloak publishes no host port in an ordinary deployment and is reachable only through nginx; the migration and rollback runbooks layer `infrastructure/docker/docker-compose.maintenance.yml` to bind `KARYO_KEYCLOAK_MAINTENANCE_PORT` (default 8181) to `127.0.0.1` for the duration of an isolated maintenance window, then take it down again.
