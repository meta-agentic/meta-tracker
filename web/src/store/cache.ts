/**
 * Cache layering for the global store.
 *
 * Two tiers, chosen by access pattern:
 *  - `AsyncCache` (IndexedDB in the browser) holds the bulky normalized board
 *    data, so a returning session paints from local cache instead of blocking on
 *    a network round-trip.
 *  - UI tracking state (active board, collapsed epics) is small and read on every
 *    interaction, so it lives in synchronous `localStorage` via `uiTracking`.
 */

export interface AsyncCache {
  get<T>(key: string): Promise<T | undefined>;
  set<T>(key: string, value: T): Promise<void>;
  delete(key: string): Promise<void>;
}

const DB_NAME = "vectis";
const STORE_NAME = "kv";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function tx<T>(
  db: IDBDatabase,
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = run(db.transaction(STORE_NAME, mode).objectStore(STORE_NAME));
    request.onsuccess = () => resolve(request.result as T);
    request.onerror = () => reject(request.error);
  });
}

/** IndexedDB-backed cache. Lazily opens a single connection and reuses it. */
export class IndexedDbCache implements AsyncCache {
  private dbPromise: Promise<IDBDatabase> | null = null;

  private db(): Promise<IDBDatabase> {
    return (this.dbPromise ??= openDb());
  }

  async get<T>(key: string): Promise<T | undefined> {
    return tx<T | undefined>(await this.db(), "readonly", (s) => s.get(key));
  }

  async set<T>(key: string, value: T): Promise<void> {
    await tx(await this.db(), "readwrite", (s) => s.put(value, key));
  }

  async delete(key: string): Promise<void> {
    await tx(await this.db(), "readwrite", (s) => s.delete(key));
  }
}

/**
 * In-memory cache — used in tests and as a fallback when IndexedDB is absent.
 * Values are structured-cloned in and out, as IndexedDB does, so what comes
 * back is a copy with the same shape a real reload would see.
 */
export class MemoryCache implements AsyncCache {
  private readonly map = new Map<string, unknown>();

  async get<T>(key: string): Promise<T | undefined> {
    const value = this.map.get(key);
    return value === undefined ? undefined : (structuredClone(value) as T);
  }

  async set<T>(key: string, value: T): Promise<void> {
    this.map.set(key, structuredClone(value));
  }

  async delete(key: string): Promise<void> {
    this.map.delete(key);
  }
}

export function createDefaultCache(): AsyncCache {
  return typeof indexedDB !== "undefined" ? new IndexedDbCache() : new MemoryCache();
}

/** Synchronous UI tracking state, persisted to localStorage when available. */
export const uiTracking = {
  read<T>(key: string, fallback: T): T {
    if (typeof localStorage === "undefined") return fallback;
    const raw = localStorage.getItem(key);
    if (raw === null) return fallback;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return fallback;
    }
  },
  write<T>(key: string, value: T): void {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, JSON.stringify(value));
  },
};
