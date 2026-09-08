import {
  Warehouse,
  Boxes,
  Users,
  LayoutDashboard,
  ClipboardList,
  Truck,
  PackageCheck,
  ArrowRightLeft,
  PackageOpen,
  Send,
  SlidersHorizontal,
  Activity,
  BarChart3,
  Tags,
  ClipboardCheck,
  LayoutGrid,
  LineChart,
  MoveVertical,
  FlaskConical,
  Layers,
  Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/** Sidebar section a nav item belongs to (Control console grouping). */
export type NavGroup =
  | 'Overview'
  | 'Inbound'
  | 'Fulfillment'
  | 'Warehouse'
  | 'Insights';

/** Count-badge tone shown next to a nav item. */
export type NavBadgeTone = 'signal' | 'warning' | 'neutral';

export interface NavItem {
  title: string;
  url: string;
  icon: LucideIcon;
  /** Required permission from JWT realm_access.roles. Undefined = visible to all. */
  permission?: string;
  group: NavGroup;
  /** Optional count badge (currently static demo counts; wire to live data later). */
  badge?: { count: number; tone: NavBadgeTone };
}

/** Render order of the sidebar groups. */
export const NAV_GROUP_ORDER: NavGroup[] = [
  'Overview',
  'Inbound',
  'Fulfillment',
  'Warehouse',
  'Insights',
];

export const navigationItems: NavItem[] = [
  { title: 'Control', url: '/', icon: LayoutDashboard, group: 'Overview' },
  { title: 'Orders', url: '/orders', icon: ClipboardList, permission: 'order-read', group: 'Inbound' },
  { title: 'ASNs', url: '/asns', icon: Truck, permission: 'order-read', group: 'Inbound' },
  { title: 'Receiving', url: '/receiving', icon: PackageCheck, permission: 'order-read', group: 'Inbound' },
  { title: 'Tasks', url: '/tasks', icon: ArrowRightLeft, permission: 'task-read', group: 'Fulfillment' },
  { title: 'Packing', url: '/packing', icon: PackageOpen, permission: 'fulfillment-read', group: 'Fulfillment' },
  { title: 'Shipments', url: '/shipments', icon: Send, permission: 'fulfillment-read', group: 'Fulfillment' },
  { title: 'Waves', url: '/waves', icon: Layers, permission: 'fulfillment-read', group: 'Fulfillment' },
  { title: 'Streaming', url: '/streaming', icon: Zap, permission: 'fulfillment-read', group: 'Fulfillment' },
  { title: 'Locations', url: '/locations', icon: Warehouse, permission: 'layout-read', group: 'Warehouse' },
  { title: 'Items', url: '/items', icon: Tags, permission: 'product-read', group: 'Warehouse' },
  { title: 'Inventory', url: '/inventory', icon: Boxes, permission: 'inventory-read', group: 'Warehouse' },
  { title: 'Cycle Count', url: '/cycle-count', icon: ClipboardCheck, permission: 'inventory-read', group: 'Warehouse' },
  { title: 'Strategies', url: '/strategies', icon: SlidersHorizontal, permission: 'order-write', group: 'Warehouse' },
  { title: 'Users', url: '/users', icon: Users, permission: 'user-admin', group: 'Warehouse' },
  // v2 redesign — INSIGHTS (no backend roles yet; visible to all).
  { title: 'Monitors', url: '/insights/monitors', icon: Activity, group: 'Insights' },
  { title: 'Reports', url: '/insights/reports', icon: BarChart3, group: 'Insights' },
  { title: 'Occupancy', url: '/insights/occupancy', icon: LayoutGrid, group: 'Insights' },
  { title: 'Forecasting', url: '/insights/forecasting', icon: LineChart, group: 'Insights' },
  { title: 'Slotting', url: '/insights/slotting', icon: MoveVertical, group: 'Insights' },
  { title: 'Simulation', url: '/insights/simulation', icon: FlaskConical, group: 'Insights' },
];

/**
 * Filter navigation items by the user's JWT permissions.
 * Items without a permission requirement are always visible.
 */
export function getVisibleNavItems(permissions: string[]): NavItem[] {
  return navigationItems.filter(
    (item) => !item.permission || permissions.includes(item.permission),
  );
}

/** Visible nav items bucketed into their sidebar groups (empty groups dropped). */
export function getGroupedNavItems(
  permissions: string[],
): Array<{ group: NavGroup; items: NavItem[] }> {
  const visible = getVisibleNavItems(permissions);
  return NAV_GROUP_ORDER.map((group) => ({
    group,
    items: visible.filter((item) => item.group === group),
  })).filter((g) => g.items.length > 0);
}
