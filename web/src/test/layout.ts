import { afterAll, beforeAll } from "vitest";

/**
 * jsdom does no layout: every element measures 0×0, so a virtualizer windows
 * nothing and any bound on rendered rows passes vacuously. Call this at the top
 * of a suite to give the elements carrying `data-testid={testId}` a real size
 * for the suite's duration; every other element keeps measuring 0.
 */
export function giveElementSize(testId: string, size: { width: number; height: number }) {
  const keys = ["offsetHeight", "offsetWidth", "clientHeight", "clientWidth"] as const;
  const originals = new Map<string, PropertyDescriptor | undefined>();

  beforeAll(() => {
    for (const key of keys) {
      const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, key);
      originals.set(key, original);
      Object.defineProperty(HTMLElement.prototype, key, {
        configurable: true,
        get(this: HTMLElement) {
          if (this.dataset.testid === testId) {
            return key.endsWith("Height") ? size.height : size.width;
          }
          return original?.get?.call(this) ?? 0;
        },
      });
    }
  });

  afterAll(() => {
    for (const [key, descriptor] of originals) {
      // clientHeight and clientWidth live on Element, so there is nothing of
      // HTMLElement's own to put back; removing the override uncovers Element's.
      if (descriptor) Object.defineProperty(HTMLElement.prototype, key, descriptor);
      else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[key];
    }
  });
}
