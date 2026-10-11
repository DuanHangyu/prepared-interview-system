import { config } from "@vue/test-utils";
import { IDBKeyRange } from "fake-indexeddb";

// Match the IndexedDB substitute used by journal fixtures; native browsers
// already provide this API. This does not replace a real-browser acceptance test.
if (typeof globalThis.IDBKeyRange === "undefined") Object.defineProperty(globalThis, "IDBKeyRange", {
  configurable: true,
  value: IDBKeyRange,
});

config.global.renderStubDefaultSlot = true;

if (typeof window !== "undefined") Object.defineProperty(window, "matchMedia", {
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

if (typeof window !== "undefined") Object.defineProperty(window, "ResizeObserver", {
  configurable: true,
  value: ResizeObserverStub,
});
Object.defineProperty(globalThis, "ResizeObserver", {
  configurable: true,
  value: ResizeObserverStub,
});
