package com.karyo.common.time

import java.time.Instant
import java.time.ZoneId

/**
 * KPI day-bucketing decision (docs/WORKLIST.md: "`KpiViewRepository` computes its query-window
 * bounds in UTC while the Postgres KPI views bucket rows with `date_trunc('day', ...)` in the
 * JDBC session's timezone" -- fixed 2026-08-17, kpi-warehouse-tz).
 *
 * **Decision: a "day" in throughput/accuracy/cycle-time KPIs (and, since demand-reader-tz, in
 * forecasting/simulation demand history) is a day in the WAREHOUSE'S OWN timezone, not UTC.**
 *
 * The Postgres KPI views (`db/migration/reporting/V1001__create_kpi_views.sql`,
 * `V1004__kpi_throughput_exclude_extinguish.sql`) and the `date_trunc('day', ...)` native
 * queries in `com.karyo.forecasting.reader.DemandReader` /
 * `com.karyo.simulation.reader.SimDemandReader` bucket rows in the JDBC session's `TimeZone`
 * GUC. pgjdbc sets that session `TimeZone` to the JVM's default zone on connect -- it is never
 * pinned to UTC anywhere in `application.yaml` or `compose-devservices.yml`. So every one of
 * these queries already buckets in "whatever timezone this deployment's container/host runs
 * with", which IS the warehouse-local decision made real by the JVM default zone: a deployment
 * chooses its warehouse's timezone by setting the container/host `TZ`, and the SQL and this code
 * then agree in every environment by construction (dev, test, and prod alike), with no separate
 * config knob to keep in sync.
 *
 * Originally introduced 2026-08-17 (kpi-warehouse-tz) inside `karyo-reporting-core` as
 * `com.karyo.reporting.WarehouseZone`. Promoted here, the same day (demand-reader-tz), once
 * `DemandReader`/`SimDemandReader` were found to have the identical UTC-vs-session-timezone
 * mismatch (docs/WORKLIST.md) -- `karyo-forecasting-core` and `karyo-simulation-core` already
 * depend on `karyo-common` (for `BaseEntity`/pagination/etc.), so referencing the constant from
 * here needs zero new Gradle edges, while a per-module duplicate would have been a second
 * hand-maintained copy of the same one-line decision. `karyo-reporting-core`'s call sites were
 * updated to this package; no deprecated alias was kept (the original object shipped hours
 * earlier the same day and had exactly two callers, both in this repo).
 */
object WarehouseZone {
    /** The zone the Postgres session (and therefore every `date_trunc('day', ...)` caller) buckets days in. */
    val ZONE: ZoneId = ZoneId.systemDefault()
}

/**
 * Converts [instant] to the SQL `DATE` of the calendar day it falls on in [zone] (default
 * [WarehouseZone.ZONE]). A top-level function (rather than a method) so the zone can be injected
 * in a plain unit test without standing up Quarkus/Postgres -- see `WarehouseZoneTest`.
 */
fun sqlDate(instant: Instant, zone: ZoneId = WarehouseZone.ZONE): java.sql.Date =
    java.sql.Date.valueOf(instant.atZone(zone).toLocalDate())
