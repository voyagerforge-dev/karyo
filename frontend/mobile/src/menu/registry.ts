/** Flat numbered transaction registry (spec section 3). Frontend mirror of backend
 *  @RolesAllowed enforcement, never a substitute. Shape chosen so a backend-served menu can
 *  replace this import without consumer changes. All items are online-only by policy. */
export interface MenuItem { id: string; num: number; label: string; route: string; roles: string[] }

export const MENU: MenuItem[] = [
  { id: 'inquiry', num: 1, label: 'Inquiry', route: '/inquiry', roles: ['inventory-read'] },
  { id: 'move', num: 2, label: 'Move', route: '/adhoc-move', roles: ['task-write'] },
  { id: 'receive', num: 3, label: 'Receive', route: '/receive-select', roles: ['order-write'] },
  { id: 'count', num: 4, label: 'Count', route: '/adhoc-count', roles: ['inventory-write'] },
  { id: 'pack', num: 5, label: 'Pack', route: '/pack', roles: ['fulfillment-write'] },
  { id: 'reprint', num: 6, label: 'Reprint', route: '/reprint', roles: ['inventory-write'] },
  { id: 'sort', num: 7, label: 'Sort', route: '/sort', roles: ['fulfillment-write'] },
  { id: 'packout', num: 8, label: 'Pack-out', route: '/packout', roles: ['fulfillment-write'] },
]
