import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // All APIs served by the karyo-app modular monolith (dev port 8080)
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/auth': {
        target: 'http://localhost:8180',
        changeOrigin: true,
        rewrite: (requestPath) => requestPath.replace(/^\/auth/, ''),
      },
    },
  },
})
