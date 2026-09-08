# Extensibility architecture

**Audience:** Extension authors and maintainers. **Reviewed:** 2026-09-08.
Use the actual API-module declarations and their consumers, not types illustrated in an old design.
This page describes existing seams; it is not a live-provider or customer-image certification.

## Module and deployment boundary

Domain `*-api` modules contain contracts; `*-core` modules implement them. APIs are Apache-2.0.
An implementation may be free or commercial. An API dependency or license token does not install
an absent engine. Compiling a paid-engine extension is different from running that engine.

Extension JARs compile against the needed public API modules and CDI dependencies, not foreign
cores. Include the chosen JAR/module in the application build **before Quarkus augmentation**,
ensure CDI discovery, then rebuild the image. A free source implementer can build their own
extended free image; a combined commercial image is a separate vendor-delivered artifact. There
is no runtime JAR upload, hot plugin discovery or promise that copying a JAR beside a running
image activates it. See [Commercial engines](../../PAID-MODULES.md) for acquisition, not private
build/signing instructions.

The [implementer cookbook](../guides/implementer-guide.md#extend-the-free-application) owns the
API-only `HeldLotStockFilter` example's build-time installation, discovery and observable safety
checks. Its example JAR carries `beans.xml` but is excluded from the normal app. The older
`HazmatStockFilter` is retained as an inactive CDI alternative, including in the augmented example
build; its pass-through implementation is not hazmat enforcement. The
[commercial catalog](../../PAID-MODULES.md) owns the separate vendor-delivery contract and evidence limits.

## 1. SPI interfaces

### Actual stock-selection contract

Declared in
[`StockSelectionFilter.kt`](../../services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/spi/StockSelectionFilter.kt):

```kotlin
interface StockSelectionFilter {
    fun filter(candidateStockUnitIds: List<Long>, request: StockSelectionRequest): List<Long>
    fun priority(): Int = 1000
}
```

The request is `com.karyo.inventory.api.vo.StockSelectionRequest`. The core discovers an
`Instance<StockSelectionFilter>`, sorts by `priority()` ascending and applies all filters. It
maps the returned IDs back to the original candidates, preserving filter order and discarding
unknown IDs. Return a subset/reordering, not invented stock. An extension must preserve domain
invariants and owner isolation; CDI code is trusted in-process code, not a sandbox.

The example's `@Alternative @Priority(1000)` enables its CDI alternative; its overridden
`priority() = 500` controls filter-chain order. These two priorities do different jobs. Do not
copy one selection rule across every SPI.

### Selection is contract-specific

| Real interface | Consumer selection | Meaning |
|---|---|---|
| `StockSelectionFilter` | Ascending `priority()`, all filters | Prune/reorder candidate IDs |
| `LocationFilter` | Ascending `priority()`, all filters | Prune/reorder built-in location candidates |
| `PutawayLocationStrategy` | Ascending `priority()`, first non-null | Replace putaway choice; null falls through |
| `OrderStrategyResolver` | Ascending priority, first non-null | Resolve the order strategy binding |
| `PackoutStrategy`, `CarrierAdapter`, `PickOrderGroupingStrategy` | Consult each resolver and key/name contract | Named strategies are not a universal priority chain |
| `WebhookSigner`, `DeliveryRetryPolicy` | Direct CDI injection | Override using CDI selection; neither interface declares `priority()` |

[ADR-036](decisions/ADR-036-strategy-driven-configuration.md) owns configuration intent. Its
priority/first-non-null convention describes that family of strategy SPIs, not every interface
in the app. See the registry and the interface's own KDoc before implementing an override.

## 2. Domain event hooks

Observe actual event classes with CDI `@Observes`. For example, `UnitLoadTransferredEvent`
lives in inventory-api's `InventoryEvents.kt`; `GoodsReceiptLineReceivedEvent` lives in
orders-api. Inspect where `Event<T>.fire` is called and the observer's transaction phase.

Default synchronous observers join the current transaction and an exception can fail the
operation. An `AFTER_SUCCESS` observer runs only after successful completion and cannot veto
an already committed receipt. `TaskService` uses that phase for auto-putaway. "Post-event" does
not itself mean notification-only, nor does "pre-event" imply a built-in veto field.

`StockSelectionCustomizer`, `GoodsReceiptCustomizer`, `PreStockReservationEvent` and the generic
pre/post hook matrix previously shown here are **not current API declarations**. There is no
stock-reservation `vetoed` flag contract or equipment-adapter implementation merely because those
names occur in design records. Use the real SPI/event source; do not implement against a sketch.

Event payload placement is a compatibility decision: `api` is the published surface foreign
modules/extensions can observe; `core` events are private to their implementation. Existing
outbox-only payloads are not automatically public hooks. Never put a cross-module payload in a
core if that would force consumers to depend on its implementation.

## 3. Strategy and runtime configuration

`OrderStrategy.extensionProperties` and `StorageStrategy.extensionProperties` carry JSONB
configuration. A property has an effect only if code reads it. Namespacing customer keys avoids
collisions; inventing a field in JSON does not implement a behavior.

Stable typed fields, strategy JSONB keys and CDI strategies are distinct choices under ADR-036.
The runtime `system_properties` ladder is another existing mechanism: client row, SYS fallback,
MicroProfile configuration, catalog default. That means a DB setting can intentionally override
an environment value. See `SystemPropertyCatalog` and `RuntimePropertyLookup` for actual keys.

Cross-docking does **not** use the old illustrative `crossDockEnabled`,
`crossDockExpiryAction` or `crossDockStagingMinutes` strategy properties. Its installed engine
uses `karyo.crossdock.*` runtime properties and an opt-in policy. Engine-specific configuration
requires its corresponding engine and entitlement, not merely the free API module.

## 4. REST webhooks

Non-JVM integrators use outbound webhooks rather than running CDI code. The
[webhook event catalog](../integration/webhook-event-catalog.md) owns the envelope, event types,
wildcard subscriptions, signature recipe and delivery limits. Fan-out and retry are asynchronous
and cannot veto the originating transaction. The relay actively reads the outbox; it is not
waiting for a future Kafka implementation.

Webhook management requires `integration-admin`. Signature verification, receiver deduplication
and safe target networking are separate responsibilities. See the
[relay README](../../services/integration-hub-service/karyo-webhooks-core/README.md) for the
existing SSRF guard and unresolved delivery-time limitations.

## Discovery and verification

`GET /api/v1/admin/extensions` (ADMIN) enumerates registered SPI implementations through
`BeanManager`. A known seam listed in that endpoint does not mean a particular example is loaded.
A registry entry proves discovery, not algorithm correctness: run a synthetic warehouse operation
and assert the intended change and an unchanged safety invariant after augmentation.

Use the existing template dependencies in
[`karyo-inventory-ext-example/build.gradle.kts`](../../services/inventory-service/karyo-inventory-ext-example/build.gradle.kts).
Do not assume illustrative `com.karyo:*:1.0.0` coordinates exist in a public Maven repository.
The checked-out module graph and release version govern source builds.

## Versioning and patch safety

[ADR-030](decisions/ADR-030-conventional-commits-semver.md) records SemVer intent. Removing or
changing a published signature is a compatibility change; additive defaulted members aim to retain
compatibility. Recompile and test extensions against the target release: Kotlin/JVM binary
compatibility is not guaranteed merely by a method having a default body or an event adding a
field. Core patches must preserve the public contract, but "never breaks an extension" is not
an unconditional technical guarantee.
