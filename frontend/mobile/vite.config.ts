import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'
import path from 'path'

export default defineConfig({
  base: '/m/',
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: null,        // we register manually in main.tsx
      manifest: false,             // public/manifest.webmanifest already provides it
      workbox: {
        globPatterns: ['**/*.{js,css,html,woff2,png,svg,wasm}'],
        navigateFallback: '/m/index.html',
        // OAuth adds query parameters, so the callback no longer matches its precache
        // key. Never replace that iframe document with the app shell: init would hang.
        navigateFallbackDenylist: [/^\/auth/, /^\/api/, /^\/m\/silent-check-sso\.html(?:\?|$)/],
        runtimeCaching: [
          {
            urlPattern: ({ url, request }: { url: URL; request: Request }) =>
              url.pathname.startsWith('/api/') && request.method === 'GET',
            handler: 'NetworkFirst' as const,
            options: { cacheName: 'api-reads', expiration: { maxAgeSeconds: 86400, maxEntries: 200 } },
          },
        ],
      },
    }),
  ],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    port: 5174,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/auth': {
        target: 'http://localhost:8180',
        changeOrigin: true,
        rewrite: (requestPath) => requestPath.replace(/^\/auth/, ''),
      },
    },
  },
})
