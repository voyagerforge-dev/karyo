import { createBrowserRouter, Navigate } from 'react-router';
import { AuthGuard } from '@/components/auth/auth-guard';
import { AdminGuard } from '@/components/auth/admin-guard';
import { AppShell } from '@/components/layout/app-shell';
import { DashboardHome } from '@/pages/home/dashboard-home';
import { LocationsPage } from '@/pages/locations/locations-page';
import { OrdersPage } from '@/pages/orders/orders-page';
import { AsnsPage } from '@/pages/asns/asns-page';
import { ReceivingPage } from '@/pages/receiving/receiving-page';
import { ReceivingWorkbench } from '@/pages/receiving/receiving-workbench';
import { TasksPage } from '@/pages/tasks/tasks-page';
import { PackingPage } from '@/pages/packing/packing-page';
import { ShipmentsPage } from '@/pages/shipments/shipments-page';
import { WavesPage } from '@/pages/waves/waves-page';
import { StreamingPage } from '@/pages/streaming/streaming-page';
import { InventoryPage } from '@/pages/inventory/inventory-page';
import { UsersPage } from '@/pages/users/users-page';
import { StrategiesPage } from '@/pages/strategies/strategies-page';
import { MonitorsPage } from '@/pages/monitors/monitors-page';
import { ReportsPage } from '@/pages/reports/reports-page';
import { OccupancyPage } from '@/pages/insights/occupancy-page';
import { ForecastingPage } from '@/pages/insights/forecasting-page';
import { SlottingPage } from '@/pages/insights/slotting-page';
import { SimulationPage } from '@/pages/insights/simulation-page';
import { ItemsPage } from '@/pages/items/items-page';
import { CycleCountPage } from '@/pages/cycle-count/cycle-count-page';
import { AdminShell } from '@/components/layout/admin-shell';
import { AdminStrategiesPage } from '@/pages/admin/admin-strategies-page';
import { AdminIntegrationsPage } from '@/pages/admin/admin-integrations-page';
import { AdminHealthPage } from '@/pages/admin/admin-health-page';
import { AdminAuditPage } from '@/pages/admin/admin-audit-page';
import { AdminClientsPage } from '@/pages/admin/admin-clients-page';
import { AdminDocumentsPage } from '@/pages/admin/admin-documents-page';
import { AdminTemplatesPage } from '@/pages/admin/admin-templates-page';
import { AdminPropertiesPage } from '@/pages/admin/admin-properties-page';
import { AdminUnitLoadTypesPage } from '@/pages/admin/admin-unit-load-types-page';
import { NotFound } from '@/pages/error/not-found';

/**
 * React Router v7 route definitions.
 * - /: protected routes wrapped in AuthGuard + AppShell (Keycloak `check-sso`;
 *   AuthGuard starts login for unauthenticated visitors and returns to `/` -
 *   silo tenancy, no tenant/workspace picker)
 * - *: 404 catch-all
 *
 * All imports from 'react-router' (not 'react-router-dom' -- merged in v7).
 */
export const router = createBrowserRouter([
  {
    element: <AuthGuard />,
    children: [
      {
        element: <AppShell />,
        children: [
          {
            path: '/',
            element: <DashboardHome />,
          },
          {
            path: '/orders',
            element: <OrdersPage />,
          },
          {
            path: '/asns',
            element: <AsnsPage />,
          },
          {
            path: '/receiving',
            element: <ReceivingPage />,
          },
          {
            path: '/receiving/:id',
            element: <ReceivingWorkbench />,
          },
          {
            path: '/tasks',
            element: <TasksPage />,
          },
          {
            // Superseded by the unified /tasks view (D3) -- Replenishment
            // is now a task type, not a separate page.
            path: '/replenishment',
            element: (
              <Navigate to={{ pathname: '/tasks', search: '?type=REPLENISH' }} replace />
            ),
          },
          {
            path: '/cycle-count',
            element: <CycleCountPage />,
          },
          {
            // Superseded by the unified /tasks view (D3) -- Picks is now a
            // task type, not a separate page.
            path: '/pick-orders',
            element: <Navigate to={{ pathname: '/tasks', search: '?type=PICK' }} replace />,
          },
          {
            path: '/packing',
            element: <PackingPage />,
          },
          {
            path: '/shipments',
            element: <ShipmentsPage />,
          },
          {
            path: '/waves',
            element: <WavesPage />,
          },
          {
            path: '/streaming',
            element: <StreamingPage />,
          },
          {
            path: '/locations',
            element: <LocationsPage />,
          },
          {
            path: '/inventory',
            element: <InventoryPage />,
          },
          {
            path: '/items',
            element: <ItemsPage />,
          },
          {
            path: '/users',
            element: <UsersPage />,
          },
          {
            path: '/strategies',
            element: <StrategiesPage />,
          },
          {
            path: '/insights/monitors',
            element: <MonitorsPage />,
          },
          {
            path: '/insights/reports',
            element: <ReportsPage />,
          },
          {
            path: '/insights/occupancy',
            element: <OccupancyPage />,
          },
          {
            path: '/insights/forecasting',
            element: <ForecastingPage />,
          },
          {
            path: '/insights/slotting',
            element: <SlottingPage />,
          },
          {
            path: '/insights/simulation',
            element: <SimulationPage />,
          },
          {
            path: '*',
            element: <NotFound />,
          },
        ],
      },
      // Admin surface — separate violet AdminShell (own sidebar/topbar).
      // AdminShell itself carries no gate: it is split into two nested
      // AdminGuards below so a principal holding only `integration-admin`
      // (e.g. `manager`) can reach /admin/integrations without also holding
      // the broader `user-admin`. AdminShell's own sidebar (admin-shell.tsx,
      // via `visibleAdminNavItems`) mirrors this split so that principal only
      // ever sees the Integrations entry, not the other admin pages it would
      // still be redirected away from.
      {
        path: '/admin',
        element: <AdminShell />,
        children: [
          // user-admin-gated admin pages (unchanged gate/behavior).
          {
            element: <AdminGuard />,
            children: [
              {
                path: 'strategies',
                element: <AdminStrategiesPage />,
              },
              {
                path: 'health',
                element: <AdminHealthPage />,
              },
              {
                path: 'audit',
                element: <AdminAuditPage />,
              },
              {
                path: 'clients',
                element: <AdminClientsPage />,
              },
              {
                path: 'documents',
                element: <AdminDocumentsPage />,
              },
              {
                path: 'document-templates',
                element: <AdminTemplatesPage />,
              },
              {
                path: 'properties',
                element: <AdminPropertiesPage />,
              },
              {
                path: 'unit-load-types',
                element: <AdminUnitLoadTypesPage />,
              },
            ],
          },
          // integration-admin-gated: narrower permission, held by `manager`.
          {
            element: <AdminGuard permission="integration-admin" />,
            children: [
              {
                path: 'integrations',
                element: <AdminIntegrationsPage />,
              },
            ],
          },
        ],
      },
    ],
  },
]);
