/**
 * Backend service URL configuration.
 * In dev mode, Vite proxy handles routing to the correct service port.
 * In production, an API gateway or reverse proxy routes these paths.
 */

export const KEYCLOAK_URL =
  import.meta.env.VITE_KEYCLOAK_URL || '/auth';

export const INVENTORY_SERVICE_URL =
  import.meta.env.VITE_INVENTORY_SERVICE_URL || '/api/v1';

export const PRODUCT_SERVICE_URL =
  import.meta.env.VITE_PRODUCT_SERVICE_URL || '/api/v1';

export const LAYOUT_SERVICE_URL =
  import.meta.env.VITE_LAYOUT_SERVICE_URL || '/api/v1';

export const AUTH_SERVICE_URL =
  import.meta.env.VITE_AUTH_SERVICE_URL || '/api/v1';
