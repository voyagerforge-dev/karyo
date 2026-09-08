/**
 * TypeScript interfaces matching auth-service DTOs.
 * @see services/auth-service/karyo-auth-api/src/main/kotlin/com/karyo/auth/dto/
 */

export interface UserResponse {
  id: string;           // Keycloak UUID -- NOT number
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  enabled: boolean;
  roles: string[];
  tenantCode: string | null;
  warehouseId: string | null;
  createdTimestamp: number | null;  // epoch millis
}

/**
 * Tenant authority granted to a new user.
 * `ops` sees and mutates every goods owner; `owner` is bound to its own client_id.
 * Server-side, only an operations administrator may grant `ops`.
 */
export type PrincipalKind = 'ops' | 'owner';

export interface CreateUserRequest {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  password: string;
  roles?: string[];
  clientId: number;
  principalKind: PrincipalKind;
  warehouseId?: string;
  forcePasswordChange?: boolean;
}

export interface UpdateUserRequest {
  email?: string;
  firstName?: string;
  lastName?: string;
  warehouseId?: string;
}

/** Available roles for assignment */
export const AVAILABLE_ROLES = [
  'ADMIN', 'MANAGER', 'OPERATOR', 'RECEIVER',
  'VIEWER', 'INTEGRATOR', 'AI_SERVICE',
] as const;
