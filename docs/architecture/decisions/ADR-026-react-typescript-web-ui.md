# ADR-026: React with TypeScript for Web UI

## Status
Accepted

## Context
Karyo WMS requires a web-based dashboard for warehouse managers, administrators, and operators (desktop usage). The dashboard must provide:

- **Real-time dashboards**: Live inventory levels, order status, warehouse utilization, operator productivity
- **Configuration management**: Warehouse layout, storage strategies, order strategies, user management
- **Order management**: Order creation, monitoring, exception handling
- **Reporting and analytics**: Historical reports, trend analysis, SLA compliance
- **Administration**: Tenant configuration, system settings, integration management

The frontend must communicate with multiple backend microservices through the API Gateway, handle complex state (real-time data updates via WebSocket/SSE, form state for multi-step workflows), and be maintainable by a team that may include frontend specialists and full-stack developers.

The frontend is separate from the mobile PWA (ADR-027) but shares API contracts and potentially some UI components. The web dashboard does not require server-side rendering (SSR) since it is an authenticated internal application, not a public-facing website.

## Decision
We will build the Karyo WMS web dashboard using **React 18+** with **TypeScript 5.x**, **Shadcn/ui** component library, **Vite** build tool, **TanStack Query** for server state, and **Zustand** for client state.

**Technology Stack:**

| Layer | Technology | Purpose |
|-------|-----------|---------|
| UI Framework | React 18+ | Component model, ecosystem |
| Language | TypeScript 5.x | Type safety, IDE support |
| Build Tool | Vite | Fast HMR, ESBuild bundling |
| Component Library | Shadcn/ui (Radix + Tailwind) | Accessible, customizable components |
| Styling | Tailwind CSS 3.x | Utility-first CSS |
| Server State | TanStack Query (React Query) | Data fetching, caching, synchronization |
| Client State | Zustand | Simple global state (UI preferences, filters) |
| Routing | React Router 6+ | Client-side routing |
| Forms | React Hook Form + Zod | Form state management, validation |
| API Types | OpenAPI Generator | TypeScript types from OpenAPI specs |
| Testing | Vitest + React Testing Library | Unit and component tests |
| E2E Testing | Playwright | End-to-end browser tests |

**Project Structure:**

```
frontend/
├── packages/
│   ├── web-dashboard/              # Main web application
│   │   ├── src/
│   │   │   ├── app/                # App shell, routing, providers
│   │   │   ├── features/           # Feature modules
│   │   │   │   ├── inventory/      # Inventory management views
│   │   │   │   ├── orders/         # Order management views
│   │   │   │   ├── warehouse/      # Layout and configuration
│   │   │   │   ├── tasks/          # Task management views
│   │   │   │   ├── reports/        # Reporting dashboards
│   │   │   │   └── admin/          # System administration
│   │   │   ├── components/         # Shared UI components
│   │   │   ├── hooks/              # Custom React hooks
│   │   │   ├── stores/             # Zustand stores
│   │   │   └── lib/                # Utilities
│   │   ├── index.html
│   │   └── vite.config.ts
│   ├── shared-types/               # Generated from OpenAPI specs
│   │   ├── src/
│   │   │   ├── inventory-api.ts    # Generated types for inventory-service
│   │   │   ├── order-api.ts        # Generated types for order-service
│   │   │   └── ...
│   │   └── package.json
│   └── ui-components/              # Shared between web and mobile PWA
│       ├── src/
│       │   ├── data-table/
│       │   ├── status-badge/
│       │   └── barcode-display/
│       └── package.json
├── pnpm-workspace.yaml
└── package.json
```

**API Integration with TanStack Query:**

```typescript
// features/inventory/api/useStockUnits.ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { StockUnitResponse, ReserveRequest } from '@karyo/shared-types';

export function useStockUnits(filters: StockUnitFilters) {
  return useQuery({
    queryKey: ['stock-units', filters],
    queryFn: () => api.get<PaginatedResponse<StockUnitResponse>>(
      '/api/v1/stock-units', { params: filters }
    ),
    staleTime: 30_000,  // Stock data considered fresh for 30s
    refetchInterval: 60_000,  // Auto-refresh every 60s for live dashboard
  });
}

export function useReserveStock() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: ReserveRequest }) =>
      api.post(`/api/v1/stock-units/${id}/reserve`, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['stock-units'] });
    },
  });
}
```

**Type Generation from OpenAPI:**

```bash
# Generate TypeScript types from each service's OpenAPI spec
# Run as part of CI pipeline when API specs change
npx openapi-typescript http://localhost:8080/q/openapi -o packages/shared-types/src/inventory-api.ts
npx openapi-typescript http://localhost:8081/q/openapi -o packages/shared-types/src/order-api.ts
```

**Real-Time Data Updates:**

```typescript
// Server-Sent Events for live dashboard updates
function useOrderUpdates(tenantId: number) {
  useEffect(() => {
    const eventSource = new EventSource(`/api/v1/orders/stream?tenantId=${tenantId}`);
    eventSource.onmessage = (event) => {
      const update = JSON.parse(event.data);
      queryClient.setQueryData(['orders', update.orderId], update);
    };
    return () => eventSource.close();
  }, [tenantId]);
}
```

## Consequences

### Positive
- React has the largest frontend ecosystem — extensive library choices, community support, hiring pool, and documentation
- TypeScript provides compile-time type safety that catches API contract mismatches early (especially with generated types from OpenAPI specs)
- Shadcn/ui provides accessible, customizable components without vendor lock-in — components are copied into the project, not installed as an opaque dependency
- Vite provides sub-second HMR (Hot Module Replacement) for rapid frontend development iteration
- TanStack Query handles data fetching complexity (caching, deduplication, background refresh, optimistic updates) without manual state management
- Monorepo with shared types ensures frontend and backend stay in sync on API contracts
- Tailwind CSS utility-first approach produces consistent styling and small bundle sizes via tree-shaking

### Negative
- React's flexibility means architectural decisions (state management, folder structure, data fetching patterns) must be explicitly defined and enforced — no framework opinions
- TypeScript adds compilation overhead and requires type definitions for all third-party libraries (though coverage is excellent for React ecosystem)
- Shadcn/ui components are project-owned — updates require manual merging, not simple package version bumps
- Frontend build toolchain complexity (Vite, TypeScript, Tailwind, pnpm workspaces) has a learning curve for backend-focused developers
- React's re-rendering model can cause performance issues with large data grids (warehouse inventory tables with thousands of rows) — requires virtualization

### Neutral
- React Server Components and the App Router pattern (Next.js) are not needed since the dashboard is a client-side SPA behind authentication; if SEO or SSR becomes relevant, Next.js can wrap the existing React components
- Zustand was chosen over Redux for simplicity — the dashboard's client-side state (UI preferences, active filters, sidebar state) is simple enough that Redux's boilerplate is not justified
- pnpm is used as the package manager for its workspace support and strict dependency resolution

## Alternatives Considered

### Alternative 1: Vue 3 (Composition API)
- **Pros**: Simpler learning curve, built-in state management (Pinia), official router, excellent TypeScript support (Vue 3), smaller bundle size, opinionated structure reduces decision fatigue
- **Cons**: Smaller ecosystem than React (fewer libraries, components, and tools), smaller hiring pool, TypeScript support is newer and less battle-tested than React+TS, fewer enterprise-scale reference implementations
- **Why rejected**: React's ecosystem advantage is significant for a complex enterprise application. The larger component library ecosystem (Shadcn/ui, Radix, headless UI), broader hiring pool, and more extensive production references outweigh Vue's simplicity advantages.

### Alternative 2: Angular
- **Pros**: Batteries-included framework (routing, forms, HTTP, DI), strong TypeScript-first approach, enterprise adoption, consistent project structure
- **Cons**: Heavy framework weight (larger bundle size), steeper learning curve (RxJS, decorators, modules), slower development velocity for simpler features, more opinionated (harder to integrate with non-Angular libraries), smaller modern component library ecosystem
- **Why rejected**: Angular's heavyweight approach is unnecessary for a dashboard application. The framework's opinions and complexity overhead do not provide proportional benefits for our use case. The RxJS learning curve is a barrier for full-stack developers who primarily work on the Kotlin backend.

### Alternative 3: Svelte/SvelteKit
- **Pros**: Excellent developer experience, smallest bundle size, compile-time reactivity (no virtual DOM), built-in animations, SvelteKit provides full framework
- **Cons**: Smallest ecosystem of the major frameworks, smallest hiring pool, fewer enterprise-scale references, fewer component library options, community is growing but smaller
- **Why rejected**: Ecosystem maturity and team pool size are risks for an enterprise warehouse management system that will be maintained long-term. The performance advantages of Svelte's compile-time approach are not critical for a dashboard application.

### Alternative 4: Next.js (React SSR/SSG)
- **Pros**: React-based (compatible with React ecosystem), SSR for fast initial load, API routes, built-in optimization (image, font, script)
- **Cons**: SSR adds server-side complexity (Node.js runtime, server deployment), introduces Vercel ecosystem coupling, overkill for an authenticated internal dashboard that does not need SEO or public-facing page speed. App Router pattern adds complexity for purely client-side applications.
- **Why rejected**: Server-side rendering is unnecessary for an authenticated warehouse dashboard. The additional deployment complexity (Node.js server, SSR caching, hydration issues) is not justified when a simple client-side SPA served from a CDN or static file server is sufficient.

## Implementation Notes
- Initialize the frontend monorepo with pnpm workspaces in the `frontend/` directory of the main repository
- Set up Vite with the React + TypeScript template; configure path aliases for clean imports (`@/features/inventory`)
- Install Shadcn/ui components incrementally as needed (data table, forms, dialogs, navigation) — do not install all components upfront
- Configure TanStack Query with a global `QueryClient` provider; set default `staleTime` and `refetchOnWindowFocus` based on data freshness requirements per feature
- Set up OpenAPI type generation in CI: when a backend service's OpenAPI spec changes, regenerate types and create a PR to update `shared-types`
- Implement a Vite proxy configuration for local development that routes `/api/*` requests to the backend API Gateway (avoids CORS issues)
- Use React.lazy and Suspense for route-based code splitting to reduce initial bundle size
- Implement a virtual scrolling solution (TanStack Virtual) for large inventory and order tables
- Create Storybook stories for shared UI components to serve as a living style guide

## Related Decisions
- [ADR-027](ADR-027-pwa-for-mobile.md): PWA for Mobile — shares API types and potentially UI components via the monorepo
- [ADR-019](ADR-019-oauth2-oidc-with-keycloak.md): OAuth2/OIDC — web dashboard uses Authorization Code with PKCE flow
- [ADR-022](superseded/ADR-022-gitops-with-argocd.md): GitOps — frontend assets deployed as static files via CDN or Kubernetes Ingress

## References
- [React Documentation](https://react.dev/)
- [TypeScript Documentation](https://www.typescriptlang.org/docs/)
- [Shadcn/ui Documentation](https://ui.shadcn.com/)
- [TanStack Query Documentation](https://tanstack.com/query)
- [Vite Documentation](https://vitejs.dev/)
- [Zustand Documentation](https://zustand-demo.pmnd.rs/)

## Revision History
- 2026-02-15: Initial version
