/*
 * Vitest setup for plugin-vm-aws.
 * Mirrors the host's test setup: stub fetch, localStorage, and the
 * observers Vuetify overlays need under jsdom.
 */
import { vi } from 'vitest'

// --- fetch -------------------------------------------------------------
globalThis.fetch = vi.fn(() =>
  Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}), text: () => Promise.resolve('') }),
)

// --- localStorage ------------------------------------------------------
const storage = new Map()
globalThis.localStorage = {
  getItem: (k) => (storage.has(k) ? storage.get(k) : null),
  setItem: (k, v) => storage.set(k, String(v)),
  removeItem: (k) => storage.delete(k),
  clear: () => storage.clear(),
}

// --- observers / viewport ---------------------------------------------
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.IntersectionObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.visualViewport = { addEventListener() {}, removeEventListener() {}, width: 1024, height: 768 }

export default {}
