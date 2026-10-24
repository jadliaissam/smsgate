import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  document.documentElement.className = ''
  try {
    localStorage.clear()
    sessionStorage.clear()
  } catch {
    // storage unavailable: nothing to reset
  }
})

// jsdom has no matchMedia; components query prefers-color-scheme and
// prefers-reduced-motion. Default: light scheme, motion allowed.
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(() => false),
    })),
  })
}
