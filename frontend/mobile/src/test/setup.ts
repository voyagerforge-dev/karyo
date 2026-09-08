import '@testing-library/jest-dom/vitest'
import 'fake-indexeddb/auto'

// jsdom has no media stack: HTMLMediaElement.play() throws "Not implemented".
// Stub it once so camera-scanner tests can drive start() without console noise.
Object.defineProperty(HTMLMediaElement.prototype, 'play', { configurable: true, value: () => Promise.resolve() })
