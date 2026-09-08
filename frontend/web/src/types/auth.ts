/**
 * Known fine-grained permissions from Keycloak composite roles.
 * These match the @RolesAllowed annotations on backend services.
 */
export type Permission =
  | 'inventory-read'
  | 'inventory-write'
  | 'product-read'
  | 'product-write'
  | 'layout-read'
  | 'layout-write'
  | 'order-read'
  | 'order-write'
  | 'task-read'
  | 'task-write'
  | 'report-read'
  | 'report-write'
  | 'ai-read'
  | 'ai-write'
  | 'integration-read'
  | 'integration-write'
  | 'user-admin'
  | 'tenant-admin';

/**
 * Auth state provided by AuthProvider context.
 */
export interface AuthState {
  initialized: boolean;
  authenticated: boolean;
  token: string | undefined;
  permissions: string[];
  userName: string | undefined;
  tenantCode: string | undefined;
  loginError: string | undefined;
  login: () => void;
  logout: () => void;
}
