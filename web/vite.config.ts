import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Same-origin in every environment: the SPA calls relative URLs and the dev
// server forwards the backend prefixes (nginx does the same in production).
const apiTarget = process.env.VITE_API_PROXY ?? 'http://localhost:8080'
const proxied = ['/app', '/v1', '/device', '/healthz']

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: Object.fromEntries(
      proxied.map((prefix) => [prefix, { target: apiTarget, changeOrigin: false, ws: prefix === '/device' }]),
    ),
  },
  build: {
    target: 'es2022',
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
  },
})
