# karyo-webhooks-core

Outbound webhook relay for Karyo WMS. Reads the active `outbox_events` log and delivers
events to subscriber endpoints via HTTPS POST with HMAC-SHA256 signing, exponential back-off
retries, and a dead-letter mechanism.

---

## What it does

1. **Fan-out** — a `@Scheduled` job reads new outbox rows (cursor-based, idempotent) and
   creates a `webhook_delivery` row for each matching `webhook_subscription`. Matching is
   done by `tenantId` equality + wildcard `eventType` pattern (`*` = all, trailing `*` =
   prefix, else exact).

2. **Delivery** — a second `@Scheduled` job picks up `PENDING` / `FAILED` deliveries whose
   `next_attempt_at` is due, builds the signed `WebhookEnvelope` JSON body, and POSTs to the
   subscriber's `targetUrl`. On 2xx the delivery is marked `DELIVERED`; on failure it is
   retried up to `max-attempts` times with back-off from `DeliveryRetryPolicy`; after that
   it becomes `DEAD`.

Both schedulers run with `ConcurrentExecution.SKIP` — if a run is still in-flight when the
next tick fires, the tick is skipped.

---

## SPI seams (ADR-036)

| SPI | Built-in | What it controls |
|-----|----------|-----------------|
| `WebhookSigner` (`karyo-webhooks-api`) | `HmacSha256Signer` | Signs the outbound request; produces the `X-Karyo-Signature` header value: `sha256=hex(HMAC-SHA256(secret, "{timestamp}.{body}"))` |
| `DeliveryRetryPolicy` (`karyo-webhooks-api`) | `ExponentialBackoffRetryPolicy` | Returns seconds to wait before the next attempt given the attempt count; built-in: `base × 2^(n-1)`, capped |

Both SPIs are directly injected, not priority chains: neither declares `priority()`. A replacement
must be selected through CDI (for example an enabled alternative), not just registered as another
`@ApplicationScoped` implementation, which would make injection ambiguous. See the
[extension contract](../../../docs/architecture/extensibility-architecture.md).

---

## Configuration knobs

All keys are under `karyo.webhooks.*` in `application.yaml`:

| Key | Default | Description |
|-----|---------|-------------|
| `poll-interval` | `5s` | How often fan-out and delivery schedulers run |
| `batch-size` | `100` | Max outbox rows or deliveries processed per tick |
| `http-timeout` | `10s` | Connect + read timeout for outbound HTTP calls |
| `max-attempts` | `8` | Delivery attempts before a delivery is marked DEAD |
| `backoff-base` | `10s` | Base duration for `ExponentialBackoffRetryPolicy` |
| `backoff-cap` | `1h` | Maximum back-off cap (built-in `ExponentialBackoffRetryPolicy`) |

---

## SSRF protection

`WebhookUrlValidator` (a plain `@ApplicationScoped` service, not yet a priority-chain SPI)
validates `targetUrl` at subscription-create time. It rejects:
- non-http(s) schemes
- loopback (127.x.x.x, ::1), link-local (169.254.x.x, fe80::/10, incl. AWS metadata IP)
- private IPv4 ranges (10/8, 172.16/12, 192.168/16) and IPv6 unique-local (fc00::/7)
- literal hostnames `localhost` and `metadata.google.internal`

For non-IP hostnames a best-effort DNS resolve is attempted at subscribe-time; if DNS is
unavailable the URL is allowed.

The two active SSRF defences are **create-time host validation** (above) plus
**`followRedirects(NEVER)`** on `WebhookHttpClient`.  There is no delivery-time re-validation.

**Residual risks documented in the v1 threat model:** DNS rebinding (TOCTOU - hostname passes
create-time DNS check, record swapped before delivery) and non-dotted-decimal IP encodings
(integer / hex-octet / octal-octet forms not detected by `looksLikeIpLiteral`).  These are
documented as residual risks in the original v1 design. The current management role is
`integration-admin`, not `user-admin`; silo deployment does not itself remove these risks.
This historical rationale is not a new acceptance of residual risk.
Delivery-time re-validation remains future hardening tracked as the
`WebhookUrlValidator` / `SsrfPolicy` candidate seam in the strategy register.

---

## Scheduler / tenant-context gotcha

Both schedulers have no JWT-primed `TenantContext`. Fan-out intentionally reads a global bounded
outbox batch by id and active subscriptions across owners, then matches `event.tenantId` to
`subscription.clientId`, rejecting SYS subscriptions. It advances a separate cursor, not the old
outbox `published` flag. Delivery reads due rows globally and processes each in a `REQUIRES_NEW`
transaction. These are not owner-filtered JPQL reads at every step. Preserve explicit owner
matching and resource scoping; never assume a Hibernate filter or ambient scheduler identity.

---

## Related files

| Path | Purpose |
|------|---------|
| `karyo-webhooks-api/spi/WebhookSigner.kt` | Signing SPI |
| `karyo-webhooks-api/spi/DeliveryRetryPolicy.kt` | Retry SPI |
| `karyo-webhooks-api/dto/WebhookEnvelope.kt` | Canonical payload shape |
| `karyo-webhooks-core/relay/WebhookFanoutScheduler.kt` | Fan-out scheduler + `OutboxReader` |
| `karyo-webhooks-core/relay/WebhookDeliveryScheduler.kt` | Delivery scheduler |
| `karyo-webhooks-core/service/WebhookUrlValidator.kt` | SSRF guard |
| `docs/integration/webhook-event-catalog.md` | Full event type catalog + envelope + signing recipe |
