import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Same-origin in every environment: the SPA calls relative URLs and the dev
// server forwards the backend prefixes (nginx does the same in production).
const apiTarget = process.env.VITE_API_PROXY ?? 'http://localhost:8080'
const backend = { target: apiTarget, changeOrigin: false }
// Whole path segments only (keys starting with ^ are regexes): a bare
// '/device' prefix would also swallow the SPA route /devices on a hard load.
const proxy = {
  '^/(app|v1)/': backend,
  '^/device/': { ...backend, ws: true },
  '^/healthz(\\?|$)': backend,
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy,
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
