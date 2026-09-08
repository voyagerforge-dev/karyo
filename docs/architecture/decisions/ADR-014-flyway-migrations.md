# ADR-014: Flyway for Database Migrations

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Mechanism unchanged; topology and execution model changed.
>
> **Flyway is still the migration tool** and migrations are still versioned and forward-only.
>
> What changed: there is one `karyo` schema rather than a database per service, so modules share a
> migration history and are separated by **reserved version ranges** (V1xx inventory, V2xx product,
> V3xx layout, V4xx orders, V5xx tasks, V6xx fulfillment, V7xx stocktaking, V11xx monitors) with
> `quarkus.flyway.out-of-order: true` — required, because a new low-range migration sorts before
> already-applied higher ranges. Migrations run at application startup, not as Kubernetes init
> containers.


## Status
Partially superseded

## Context
Karyo WMS uses database-per-service (see [ADR-004](superseded/ADR-004-database-per-service.md)) with PostgreSQL (see [ADR-005](ADR-005-postgresql-database.md)). Each of the 10 services manages its own database schema, which must evolve independently as features are added, indexes are tuned, and tables are partitioned.

Schema management requirements:
1. **Version tracking:** Every schema change must be tracked and applied in order. "What version is this database at?" must be answerable.
2. **Reproducibility:** A fresh database must reach the current schema state by running all migrations in sequence. Development, staging, and production must use the same migration scripts.
3. **Rollback strategy:** While DDL changes in PostgreSQL are transactional, not all changes are easily reversible (e.g., dropping a column). A forward-only strategy with corrective migrations is simpler and safer.
4. **CI/CD integration:** Migrations must run automatically during deployment, either as part of service startup or as a pre-deployment step.
5. **Team independence:** Each service team manages its own migration scripts without coordinating version numbers with other services.

## Decision
We will use **Flyway** for database schema versioning and migration across all Karyo WMS services.

### Migration Script Organization

```
services/{service-name}/src/main/resources/db/migration/
├── V1__create_stock_units.sql
├── V2__create_unit_loads.sql
├── V3__create_unit_load_types.sql
├── V4__create_inventory_journals.sql
├── V5__add_stock_unit_indexes.sql
├── V6__add_journal_partitioning.sql
├── V7__add_outbox_table.sql
├── R__create_views.sql              # Repeatable migration for views/functions
```

### Naming Conventions

| Type | Pattern | Example | When |
|------|---------|---------|------|
| Versioned | `V{version}__{description}.sql` | `V1__create_stock_units.sql` | Schema changes (CREATE, ALTER, DROP) |
| Repeatable | `R__{description}.sql` | `R__create_reporting_views.sql` | Views, functions, procedures (re-run on change) |

- Version numbers are integers (V1, V2, V3...) per service, NOT globally unique.
- Descriptions use snake_case: `create_stock_units`, `add_fifo_index`, `partition_journals`.
- Each migration file is idempotent where possible (use `IF NOT EXISTS`, `IF EXISTS`).

### Migration Execution Strategy

**Kubernetes deployment:** Migrations run as **init containers** before the service pod starts.

```yaml
initContainers:
  - name: flyway-migrate
    image: flyway/flyway:10
    args: ["migrate"]
    env:
      - name: FLYWAY_URL
        valueFrom:
          secretKeyRef:
            name: inventory-db-secret
            key: jdbc-url
      - name: FLYWAY_USER
        valueFrom:
          secretKeyRef:
            name: inventory-db-secret
            key: username
      - name: FLYWAY_PASSWORD
        valueFrom:
          secretKeyRef:
            name: inventory-db-secret
            key: password
    volumeMounts:
      - name: migrations
        mountPath: /flyway/sql
```

**Alternative:** Quarkus Flyway extension can run migrations on application startup (`quarkus.flyway.migrate-at-start=true`). Used for development and small deployments. Init containers are preferred for production because migration failures do not crash the application pod.

### Migration Patterns

**Creating tables (idempotent):**
```sql
-- V1__create_stock_units.sql
CREATE TABLE IF NOT EXISTS stock_units (
    id BIGSERIAL PRIMARY KEY,
    version INT NOT NULL DEFAULT 0,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id BIGINT NOT NULL,
    item_data_id BIGINT NOT NULL,
    amount NUMERIC(17,4) NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(17,4) NOT NULL DEFAULT 0,
    state INT NOT NULL DEFAULT 0,
    lock_type INT NOT NULL DEFAULT 0,
    lot_number VARCHAR(255),
    serial_number VARCHAR(255),
    best_before DATE,
    strategy_date TIMESTAMPTZ,
    unit_load_id BIGINT NOT NULL
);
```

**Adding indexes:**
```sql
-- V5__add_stock_unit_indexes.sql
CREATE INDEX IF NOT EXISTS idx_stock_units_picking
    ON stock_units(item_data_id, state, lock_type)
    WHERE state = 300 AND lock_type = 0;

CREATE INDEX IF NOT EXISTS idx_stock_units_fifo
    ON stock_units(strategy_date, amount, created, id);

CREATE INDEX IF NOT EXISTS idx_stock_units_unit_load
    ON stock_units(unit_load_id);
```

**Adding columns:**
```sql
-- V8__add_stock_unit_packaging.sql
ALTER TABLE stock_units ADD COLUMN IF NOT EXISTS packaging_unit_id BIGINT;
```

**Outbox table (per-service):**
```sql
-- V7__add_outbox_table.sql
CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox(created) WHERE published = FALSE;
```

### Rollback Strategy

**Forward-only migrations.** If a migration causes issues in production:

1. **Corrective migration:** Create a new migration (e.g., `V9__revert_packaging_column.sql`) that reverses the change.
2. **Never modify existing migrations:** Once a migration has been applied to any environment, its content is immutable.
3. **Test migrations in staging:** All migrations run in staging before production. Use `flyway validate` in CI to ensure checksums match.

### Cross-Service Reference Constraints

Per [ADR-004](superseded/ADR-004-database-per-service.md), cross-service references do NOT have foreign keys:

```sql
-- CORRECT: Cross-service reference without FK
ALTER TABLE stock_units ADD COLUMN item_data_id BIGINT NOT NULL;
-- NO FK to karyo_product.item_data — validated at application level

-- CORRECT: Intra-service reference WITH FK
ALTER TABLE stock_units ADD CONSTRAINT fk_stock_unit_load
    FOREIGN KEY (unit_load_id) REFERENCES unit_loads(id);
```

## Consequences

### Positive
- **Version-controlled schema:** Every database change is tracked in Git alongside the service code. "What schema is running in production?" is answerable by checking the `flyway_schema_history` table.
- **Reproducible environments:** Running all migrations on a fresh database produces the exact same schema as production. Development, staging, and production use identical migration scripts.
- **Independent per-service:** Each service's migration numbering is independent (V1 in inventory-service is different from V1 in order-service). Teams do not coordinate version numbers.
- **CI/CD integration:** Flyway init containers (Kubernetes) or startup migration (Quarkus) automatically apply pending migrations during deployment. No manual DDL scripts.
- **Quarkus integration:** The `quarkus-flyway` extension provides zero-config Flyway for development mode (Dev Services automatically migrates the auto-provisioned PostgreSQL).
- **Audit trail:** The `flyway_schema_history` table records when each migration was applied, providing a deployment audit trail.

### Negative
- **Forward-only complexity:** Reverting a production schema change requires a new corrective migration, not a simple "undo." This adds an extra migration file and increases the migration count. Mitigated by thorough staging testing.
- **Migration ordering sensitivity:** Migrations must be applied in strict order. If two developers create V5 independently, Flyway will reject the second one (checksum conflict). Mitigated by using timestamps as version numbers or coordinating migration numbering within the team.
- **Large data migrations are slow:** Migrations that modify large tables (e.g., adding a NOT NULL column to `inventory_journals` with millions of rows) can take significant time and lock the table. Mitigated by using PostgreSQL's `ADD COLUMN ... DEFAULT ...` (fast in PostgreSQL 11+) and avoiding `NOT NULL` on columns without defaults for existing rows.
- **Init container adds deployment time:** Flyway init containers add 5-30 seconds to pod startup (depending on migration count and complexity). Mitigated by fast PostgreSQL connections and minimal migration logic.

### Neutral
- Flyway Community Edition is sufficient for all Karyo requirements. Flyway Teams (paid) adds undo migrations and dry-run, which are nice-to-have but not necessary with the forward-only strategy.
- Repeatable migrations (`R__`) are re-run whenever their content changes, making them suitable for views, functions, and procedures that are fully re-creatable.

## Alternatives Considered

### Alternative 1: Liquibase
- **Pros**: XML/YAML/JSON changeset format (more structured than SQL), built-in rollback support, database-agnostic changeset format, extensive change types.
- **Cons**: XML changesets are verbose and harder to review than plain SQL, Liquibase abstraction layer can hide PostgreSQL-specific optimizations (partial indexes, table partitioning), slower execution for complex migrations, larger dependency footprint.
- **Why rejected**: For a PostgreSQL-only project, plain SQL migrations are clearer, more reviewable, and allow direct use of PostgreSQL features (partial indexes, table partitioning, RLS). Liquibase's database-agnostic abstraction provides no benefit when the target database is always PostgreSQL. SQL migrations are also more familiar to DBAs who may review migration scripts.

### Alternative 2: Hibernate auto-update (`hbm2ddl.auto=update`)
- **Pros**: Zero migration files needed, schema automatically matches entity definitions, fastest development iteration.
- **Cons**: Cannot drop columns or tables (only additive), cannot create indexes or constraints not mapped to entities, unpredictable DDL in production, no version tracking, no audit trail, cannot handle data migrations, Hibernate documentation explicitly warns against production use.
- **Why rejected**: Hibernate auto-update is unsuitable for production. It cannot handle partial indexes (critical for stock selection performance), table partitioning (critical for InventoryJournal), or data migrations. The lack of version tracking and reproducibility makes it impossible to audit or troubleshoot schema issues. Acceptable only for local development prototyping.

### Alternative 3: Manual SQL Scripts
- **Pros**: Full control, no tool dependency, simplest possible approach.
- **Cons**: No version tracking (which scripts have been applied?), no ordering guarantee, no checksum validation, easy to forget to apply a script, no CI/CD automation, error-prone in team environments.
- **Why rejected**: Manual script management does not scale to 10 services with independent teams. Version tracking, ordering, and automated application are essential for reliable deployments.

## Implementation Notes
- **Quarkus configuration:**
  ```properties
  # application.properties
  quarkus.flyway.migrate-at-start=true  # For dev mode
  quarkus.flyway.locations=db/migration
  quarkus.flyway.schemas=karyo_inventory
  ```
- **Production (K8s):** Disable `migrate-at-start`, use Flyway init container instead.
- **CI validation:** Run `flyway validate` in CI to detect modified migration files (checksum mismatch).
- **Seed data:** Use `V{n}__seed_{data}.sql` for essential reference data (default UnitLoadTypes, Areas, Zones). Do NOT use Flyway for test data — use `import-test.sql` for test fixtures.
- **Migration numbering:** Use sequential integers (V1, V2, V3). If parallel development causes conflicts, resolve by renumbering before merge.

## Related Decisions
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md)

## References
- Flyway: https://flywaydb.org/
- Quarkus Flyway guide: https://quarkus.io/guides/flyway
- Flyway best practices: https://flywaydb.org/documentation/concepts/migrations#best-practices

## Revision History
- 2026-02-15: Initial version
