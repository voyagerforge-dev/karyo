/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_KEYCLOAK_URL: string;
  readonly VITE_INVENTORY_SERVICE_URL: string;
  readonly VITE_PRODUCT_SERVICE_URL: string;
  readonly VITE_LAYOUT_SERVICE_URL: string;
  readonly VITE_AUTH_SERVICE_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
