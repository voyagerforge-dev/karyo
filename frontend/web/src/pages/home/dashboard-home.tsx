import { OperationsControl } from '@/pages/home/ops/operations-control';

/**
 * The app's home screen (`/`). Renders the Operations Control dashboard — the
 * floor manager's real-time command center. The router imports `DashboardHome`
 * from this file; the implementation lives in `ops/operations-control.tsx`.
 */
export function DashboardHome() {
  return <OperationsControl />;
}
