# ADR-027: Progressive Web App for Mobile

## Status
Accepted

## Context
Warehouse operators are the primary users of Karyo WMS's mobile interface. They use the mobile application throughout their shift for:

- **Picking**: Scan source location, confirm item, scan destination container
- **Receiving**: Scan incoming goods, verify against advice lines, confirm quantities
- **Putaway**: Scan unit load, receive directed location, confirm placement
- **Shipping**: Scan packages, confirm loading, complete shipment
- **Stocktaking**: Scan locations, count items, record discrepancies
- **Replenishment**: Pick from reserve, deliver to forward pick locations

The mobile interface must work on:
- **Consumer Android/iOS devices** (smartphones, tablets)
- **Enterprise mobile computers** (Zebra TC52/TC72, Honeywell CT40/CT60) with built-in barcode scanners
- **Warehouse environments**: spotty WiFi coverage, concrete walls, extreme temperatures (cold chain), operators wearing gloves

Key requirements:
- **Offline capability**: Core workflows (picking, receiving) must function during WiFi dropouts
- **Barcode scanning**: Must scan 1D/2D barcodes reliably and quickly
- **Single-hand operation**: Many tasks performed while holding/moving goods
- **Shared devices**: Operators may share devices across shifts (fast login/logout)
- **Battery life**: Full shift (8-12 hours) without charging

## Decision
We will build the Karyo WMS mobile interface as a **Progressive Web App (PWA)** rather than native mobile applications.

**Architecture:**

```
┌─────────────────────────────────────────────────┐
│              Mobile PWA (React + TS)             │
│                                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ Picking  │  │Receiving │  │ Putaway  │  ...  │
│  │ Module   │  │ Module   │  │ Module   │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │              │              │             │
│  ┌────▼──────────────▼──────────────▼────┐       │
│  │         Service Worker                 │       │
│  │  (offline caching, background sync)    │       │
│  └────────────────────┬──────────────────┘       │
│                       │                           │
│  ┌────────────────────▼──────────────────┐       │
│  │         IndexedDB                      │       │
│  │  (offline data store, operation queue) │       │
│  └───────────────────────────────────────┘       │
└──────────────────────┬────────────────────────────┘
                       │
                       │ HTTPS
                       ▼
              ┌─────────────────┐
              │ mobile-api-gw   │  ← BFF (Backend for Frontend)
              │                 │     Optimized payloads
              │                 │     Session management
              └────────┬────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │inventory │ │  order   │ │  task    │
    │ service  │ │ service  │ │ service  │
    └──────────┘ └──────────┘ └──────────┘
```

**PWA Capabilities Used:**

| Capability | Web API | Purpose |
|-----------|---------|---------|
| Offline | Service Worker + Cache API | Cache UI shell and critical data for offline use |
| Data Persistence | IndexedDB | Store pending operations, cached inventory data |
| Background Sync | Background Sync API | Replay queued operations when connectivity returns |
| Camera | MediaDevices API (getUserMedia) | Barcode scanning via camera |
| Push Notifications | Web Push API + Notification API | Task assignments, order alerts |
| Install | Web App Manifest | Add to home screen, fullscreen mode |
| Vibration | Vibration API | Haptic feedback on scan success/failure |

**Barcode Scanning Strategy:**

```typescript
// Camera-based scanning using a web barcode scanning library
// Primary: ZXing.js (open-source, supports 1D + 2D barcodes)
// Fallback: Native scanner input via keyboard wedge mode (enterprise devices)

interface BarcodeScannerConfig {
  preferNativeScanner: boolean;  // Enterprise devices with built-in scanners
  cameraFacing: 'environment' | 'user';  // Rear camera default
  formats: BarcodeFormat[];  // CODE_128, EAN_13, QR_CODE, DATA_MATRIX
  continuousMode: boolean;  // Keep scanning after each read
}

// Enterprise scanner devices (Zebra, Honeywell) output scans as keyboard input
// The PWA intercepts keyboard input matching barcode patterns
function useHardwareScanner(): ScanResult {
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      // Accumulate rapid keystrokes (< 50ms between keys)
      // When Enter is pressed, treat accumulated string as barcode
      // This works with ALL enterprise scanners in keyboard wedge mode
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);
}
```

**Offline-First Architecture:**

```typescript
// Offline operation queue stored in IndexedDB
interface PendingOperation {
  id: string;
  type: 'pick_confirm' | 'receive_stock' | 'transfer_unitload' | 'count_stock';
  payload: unknown;
  createdAt: string;
  retryCount: number;
  status: 'pending' | 'syncing' | 'failed';
}

// Service worker background sync handler
self.addEventListener('sync', (event: SyncEvent) => {
  if (event.tag === 'sync-operations') {
    event.waitUntil(syncPendingOperations());
  }
});

async function syncPendingOperations() {
  const db = await openDB('karyo-mobile');
  const pending = await db.getAll('pending-operations');

  for (const op of pending) {
    try {
      await sendToServer(op);
      await db.delete('pending-operations', op.id);
    } catch (error) {
      // Increment retry count, will retry on next sync
      op.retryCount++;
      await db.put('pending-operations', op);
    }
  }
}
```

**Offline Data Caching:**

| Data | Cache Strategy | Freshness |
|------|---------------|-----------|
| UI shell (HTML, JS, CSS) | Cache-first, update in background | On deployment |
| Active pick list | Pre-cached on assignment | Before pick walk |
| Location inventory (current task) | Pre-cached on task start | Before operation |
| Product catalog (active items) | Cached on login, incremental updates | Every 15 min |
| Location names/barcodes | Cached on login | Daily |

**Mobile-API-Gateway (BFF):**

A Backend-for-Frontend service optimizes API interactions for mobile:
- Aggregates data from multiple backend services into single mobile-optimized responses
- Compresses payloads (mobile bandwidth may be limited)
- Manages mobile session state
- Queues operations received from offline-sync for idempotent processing
- Provides SSE (Server-Sent Events) for push updates to mobile clients

## Consequences

### Positive
- Single codebase serves all platforms (iOS, Android, enterprise scanners) — no separate native development for each platform
- Instant deployment — update the web app once, all devices get the latest version on next load (no app store review process)
- Shared code with web dashboard (ADR-026) — React components, TypeScript types, and API client code reused via monorepo
- No native SDK dependency for barcode scanning — camera API works on all modern browsers, enterprise scanners use keyboard wedge mode
- Web Push notifications provide mobile alerting without native push infrastructure
- PWA install capability provides app-like experience (fullscreen, home screen icon) without app store distribution
- Lower development and maintenance cost than maintaining separate iOS and Android codebases

### Negative
- Camera-based barcode scanning is slower and less reliable than native scanner SDKs (especially in poor lighting, at distance, or with damaged barcodes)
- iOS Safari has limited PWA support compared to Chrome/Android (no background sync, limited push notification support, storage eviction)
- Offline capability is limited to pre-cached data — operations requiring server-side validation (e.g., checking stock availability) must either be cached optimistically or deferred
- Service worker lifecycle management adds complexity (version updates, cache invalidation, stale worker issues)
- Enterprise scanner devices may have older browser versions with limited Web API support
- No access to platform-specific features: NFC (for RFID tags), Bluetooth (for external scanners), custom hardware APIs

### Neutral
- Enterprise scanners from Zebra and Honeywell ship with Chrome-based browsers that support all required PWA features; keyboard wedge mode for barcode input is universally supported
- If camera-based scanning proves insufficient for a specific customer, a thin native wrapper (Capacitor or TWA) can expose native scanner SDKs while keeping the PWA codebase
- Battery consumption is comparable between PWA and native apps for the warehouse scanning use case (screen on, periodic network, camera bursts)

## Alternatives Considered

### Alternative 1: React Native
- **Pros**: Native performance, access to native scanner SDKs (Zebra EMDK, Honeywell SDK), native push notifications, native UI components, offline storage via SQLite or Realm, access to Bluetooth/NFC
- **Cons**: Two codebases (web dashboard uses React DOM, mobile uses React Native), native bridge complexity for scanner integration, separate build/deploy pipeline for iOS and Android, app store distribution adds release cycle overhead, cannot share UI components directly with web dashboard
- **Why rejected**: The fundamental issue is maintaining two separate UI codebases (React DOM for web, React Native for mobile). The web dashboard and mobile app share the same API contracts and many UI patterns; having them in different rendering frameworks doubles the UI development effort. Enterprise scanner devices already support PWA well, and keyboard wedge mode provides reliable barcode scanning without native SDKs.

### Alternative 2: Flutter
- **Pros**: Single codebase for iOS and Android with truly native compilation, excellent performance, rich widget library, strong offline support via Hive/Drift, cross-platform consistency
- **Cons**: Dart language is separate from the TypeScript web ecosystem, no code sharing with the React web dashboard, separate build toolchain, smaller plugin ecosystem than React Native, requires Dart expertise (separate from the web team's TypeScript skills)
- **Why rejected**: Introducing Dart and Flutter creates a completely separate technology stack from the web dashboard. The team would need expertise in Kotlin (backend), TypeScript (web), and Dart (mobile) — three languages. PWA allows the team to use TypeScript and React for both web and mobile.

### Alternative 3: Native iOS + Native Android
- **Pros**: Best possible performance, full access to all platform APIs (scanner SDKs, NFC, Bluetooth, biometrics), best offline support, app store distribution with controlled rollout
- **Cons**: Double the development effort (Swift/Kotlin or Objective-C/Java), double the testing effort, double the deployment pipeline, separate codebases with no code sharing, app store review delays for updates, hiring two separate mobile teams
- **Why rejected**: The cost of maintaining two native mobile codebases is prohibitive for a warehouse management system. The mobile interface is primarily forms, lists, and barcode scanning — these do not require native performance. The PWA approach provides sufficient capabilities at a fraction of the development cost.

## Implementation Notes
- Build the mobile PWA as a separate package in the frontend monorepo (`packages/mobile-pwa/`) sharing types and UI components with the web dashboard
- Use Workbox (Google) for Service Worker management — it handles caching strategies, background sync, and precaching with a declarative configuration
- Implement the camera barcode scanner using `@nicedoc/barcode-scanner` or `html5-qrcode` library; test extensively on enterprise scanner devices (Zebra TC52, Honeywell CT40)
- Design the mobile UI for single-hand operation: large touch targets (minimum 48px), bottom-anchored action buttons, swipe navigation, minimal typing (scan-driven workflows)
- Implement optimistic UI updates: when an operator confirms a pick offline, immediately update the local UI and queue the server sync
- For the mobile-api-gateway (BFF): implement in Quarkus as a lightweight service that aggregates backend calls, handles mobile session management, and provides SSE endpoints for push updates
- Test PWA on iOS Safari regularly — Apple's PWA support evolves slowly and has known limitations (storage eviction after 7 days of non-use, no background sync)
- Create a device compatibility matrix documenting tested enterprise scanners, browser versions, and feature availability

## Related Decisions
- [ADR-026](ADR-026-react-typescript-web-ui.md): React + TypeScript for Web UI — shared monorepo, types, and components
- [ADR-019](ADR-019-oauth2-oidc-with-keycloak.md): OAuth2/OIDC — mobile uses PKCE flow for authentication
- [ADR-020](ADR-020-multi-tenancy-strategy.md): Multi-Tenancy — mobile BFF routes requests to correct tenant context

## References
- [web.dev: Progressive Web Apps](https://web.dev/progressive-web-apps/)
- [Workbox Documentation](https://developer.chrome.com/docs/workbox/)
- [Web App Manifest Specification](https://www.w3.org/TR/appmanifest/)
- [Barcode Detection API (emerging)](https://developer.mozilla.org/en-US/docs/Web/API/Barcode_Detection_API)
- [Zebra Enterprise Browser for Android](https://www.zebra.com/us/en/support-downloads/software/developer-tools/enterprise-browser.html)

## Revision History
- 2026-02-15: Initial version
