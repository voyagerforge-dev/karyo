/**
 * TypeScript interfaces matching karyo-auth SystemPropertyDtos (SC16 runtime-config store).
 * @see services/auth-service/karyo-auth-api/src/main/kotlin/com/karyo/auth/dto/SystemPropertyDtos.kt
 */

/** Where an effective value was resolved from — SC16's fallback ladder. */
export type PropertySource = 'CLIENT' | 'SYSTEM' | 'CONFIG' | 'DEFAULT';

export type SystemPropertyType = 'STRING' | 'BOOLEAN' | 'INTEGER';

/**
 * One row of the system-properties screen: either a catalog key with its resolved effective
 * value, or a stored non-catalog row (`type`/`group`/`description`/`defaultValue` are `null`
 * for those — grouped under "Custom" on the frontend).
 */
export interface SystemPropertyView {
  key: string;
  context: string | null;
  clientId: number;
  value: string | null;
  source: PropertySource;
  type: SystemPropertyType | null;
  group: string | null;
  description: string | null;
  defaultValue: string | null;
  /** Write-only catalog key: `value` is the literal mask ("••••••"), never the raw value. */
  secret: boolean;
  /** False = operator-controlled: a goods-owner principal's PUT/DELETE on this key 403s. */
  ownerWritable: boolean;
}

/** Body for `PUT /api/v1/system-properties/{key}`. */
export interface UpsertSystemPropertyRequest {
  value: string;
  context?: string;
  description?: string;
  clientId?: number;
}
