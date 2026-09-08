# Karyo WMS — Webhook Event Catalog

> Outbound webhooks are delivered by the `karyo-webhooks-core` relay. It reads domain events
> from the active `outbox_events` log by cursor and fans them out to matching subscriptions.
> Event coverage depends on the writing path; this is not a guarantee of exhaustive mutation audit.

---

## Envelope shape

Every delivery is a JSON POST whose body is a `WebhookEnvelope`:

```json
{
  "eventId":       "string  — deterministic UUID for this delivery row, stable across retries (idempotency key)",
  "eventType":     "string  — e.g. 'ItemDataCreated'",
  "occurredAt":    "string  — ISO-8601 timestamp (UTC)",
  "tenantId":      "number  — Karyo client_id (the goods-owner tenant)",
  "aggregateType": "string  — the domain entity class, e.g. 'ItemData'",
  "aggregateId":   "number  — database primary key of the aggregate (0 for User events; see caveat)",
  "data":          "object  — event-specific payload (varies by eventType)"
}
```

Source: `services/integration-hub-service/karyo-webhooks-api/src/main/kotlin/com/karyo/webhooks/dto/WebhookEnvelope.kt`

---

## Request headers

| Header | Value |
|--------|-------|
| `Content-Type` | `application/json` |
| `X-Karyo-Event` | `{eventType}` — e.g. `ItemDataCreated` |
| `X-Karyo-Delivery` | Delivery row's numeric id as a string, stable across retries; body `eventId` is the derived UUID |
| `X-Karyo-Timestamp` | `{Unix epoch seconds}` — used in signature |
| `X-Karyo-Signature` | `sha256={hex(HMAC-SHA256(secret, "{timestamp}.{rawBody}"))}` |

---

## Signature verification

The signature is computed over the concatenation of the timestamp and the raw request body,
separated by a literal period:

```
signingInput = "{X-Karyo-Timestamp}.{rawBody}"
expected     = "sha256=" + hex(HMAC-SHA256(subscriptionSecret, signingInput))
```

**Verification recipe (Python):**

```python
import hmac, hashlib

def verify(secret: str, timestamp: str, raw_body: bytes, header_sig: str) -> bool:
    signing_input = f"{timestamp}.".encode() + raw_body
    mac = hmac.new(secret.encode(), signing_input, hashlib.sha256)
    computed = "sha256=" + mac.hexdigest()
    return hmac.compare_digest(computed, header_sig)
```

Always use a constant-time comparison (`hmac.compare_digest` / `MessageDigest.isEqual`) to
prevent timing attacks.

---

## Subscription wildcard rules

When creating a subscription, the `eventTypes` field is a list of patterns.
A delivery is created when **any pattern matches** the outbox row's `eventType`:

| Pattern | Matches |
|---------|---------|
| `*` | every event type |
| `PickOrder*` | any event type starting with `PickOrder` (prefix match) |
| `ItemDataCreated` | only the exact event type `ItemDataCreated` (no wildcard) |

Rule: a trailing `*` is a prefix match; `*` alone matches all; anything else is an exact match.

There is no server-side allowlist of event types - `WebhookFanoutScheduler` matches subscriptions
against whatever `eventType` an outbox row carries. A new event type is therefore delivered to
existing `*` and matching-prefix subscriptions the moment it is first published: `PickOrderReleased`
(added 2026-09-06) reaches every subscription already on `PickOrder*`.

---

## Tenant caveat — tenant_id 0 (system events)

The `outbox_events` table stores a `tenant_id` on every row. Outbox rows written outside a
request context (e.g. system-initiated batch jobs or future Keycloak auth events) may carry
`tenant_id = 0`. The fan-out scheduler skips these rows for all normal subscriptions because
it requires nonzero `subscription.clientId` and equality with `e.tenantId`. A subscription for tenant 1 will **never**
receive `tenant_id = 0` events. There is no mechanism to subscribe to system-level events in v1.5.

---

## Event type table

| Module | aggregateType | eventType | Trigger |
|--------|--------------|-----------|---------|
| **INVENTORY** | `StockUnit` | `StateChanged` | Stock unit created or state advanced (e.g. INCOMING → ON_STOCK → PICKED) |
| | `StockUnit` | `AmountChanged` | Amount adjusted, reserved, or transferred |
| | `StockUnit` | `LockChanged` | Lock type changed on a stock unit |
| | `StockUnit` | `Deleted` | Stock unit deleted (state set to DELETABLE and removed) |
| | `UnitLoad` | `UnitLoadTransferred` | Unit load moved to a new storage location |
| **PRODUCT** | `ItemData` | `ItemDataCreated` | New product (ItemData) created |
| | `ItemData` | `ItemDataUpdated` | Product fields updated (non-state change) |
| | `ItemData` | `ItemDataStateChanged` | Product state toggled (ACTIVE ↔ INACTIVE) |
| | `ItemData` | `ItemDataDeleted` | Product deleted |
| **LAYOUT** | `StorageLocation` | `LocationLockChanged` | Storage location lock type changed |
| | `StorageLocation` | `LocationAllocationChanged` | Storage location allocation % updated (triggered by unit load moves) |
| **ORDERS** | `Asn` | `AsnStateChanged` | Advance Shipment Notice state advanced |
| | `GoodsReceipt` | `GoodsReceiptLineReceived` | A single ASN line received during goods receipt |
| | `GoodsReceipt` | `GoodsReceiptStateChanged` | Goods receipt state advanced |
| | `DeliveryOrder` | `DeliveryOrderStateChanged` | Outbound delivery order state advanced |
| **TASKS** | `TransportOrder` | `TransportOrderStateChanged` | Transport order state advanced |
| | `TransportOrder` | `TransportOrderCompleted` | Transport order reached terminal FINISHED state |
| **FULFILLMENT** | `PickOrder` | `PickOrderCreated` | Pick order created (release to picking) |
| | `PickOrder` | `PickOrderPicked` | All picks on a pick order confirmed |
| | `PickOrder` | `PickOrderReleased` | A claimed pick order was handed back to the pool (STARTED → RELEASED). `data` carries `releasedFrom` (the operator who held it, nullable), `releasedBy` (the operator who performed the release) and `managerOverride` (true only when those two differ) |
| | `Pick` | `PickShortfallReported` | Short-pick remainder reported (PARTIAL_SHIP strategy) |
| | `Shipment` | `ShipmentStateChanged` | Shipment state advanced (MANIFESTED → DISPATCHED → SHIPPED) |
| **STOCKTAKING** | `CountOrder` | `CountOrderReleased` | A claimed count order was handed back to the pool (state stays GENERATED). Same `releasedFrom` / `releasedBy` / `managerOverride` shape as `PickOrderReleased` |
| **MONITORS** | `alert` | `alert.fired` | Monitor detector opened a new alert episode (first fire for a scope with no open alert; ongoing/re-seen episodes do not re-emit). `data` is the `AlertDto` shape: `id`, `monitorKey`, `monitorName`, `severity`, `status`, `scope`, `reason`, `suggestedFix`, `observedValue`, `firstFiredAt`, `lastSeenAt`, `resolvedAt` |
| **AUTH** | `User` | `UserCreated` | New user provisioned in Keycloak |
| | `User` | `UserDeactivated` | User account disabled |
| | `User` | `UserReactivated` | User account re-enabled |
| | `User` | `RoleAssigned` | Realm role assigned to a user |
| | `User` | `RoleRevoked` | Realm role removed from a user |

> **Auth event caveat:** User aggregates live in Keycloak, not in the Karyo database.
> `aggregateId` is always `0` for `User` events. Use `data.userId` (a Keycloak UUID string)
> as the user identifier.

---

## Ping event

A `POST /api/v1/webhooks/{id}/ping` creates a synthetic delivery with:

```json
{
  "eventType": "webhook.ping",
  "aggregateType": "Webhook",
  "aggregateId": 0,
  "data": { "message": "ping" }
}
```

Ping deliveries are not backed by an outbox row (`outboxEventId` is null). They follow the
same signing and retry path as real deliveries.

---

## Delivery retry behaviour

Failed deliveries (non-2xx response or connection error) are retried up to **8 attempts**
(default) with exponential back-off starting at 10 s (base × 2^(n-1), capped at 1 h by
default). After the maximum attempts the delivery is marked `DEAD` and visible in the Admin
Integrations screen. Dead deliveries can be manually requeued via
`POST /api/v1/webhook-deliveries/{id}/redeliver`. A manual redeliver resets the attempt
counter to 0, granting the delivery a full fresh retry budget.

---

## Security / SSRF

The [relay's SSRF contract](../../services/integration-hub-service/karyo-webhooks-core/README.md#ssrf-protection)
owns target validation, redirect refusal, the management-role boundary and unresolved
DNS/IP-encoding risks. Read those limits before registering a receiver; the original v1 rationale
is not a new acceptance of residual risk.
