/// <reference types="vite/client" />

/**
 * The build-time configuration `src/api/config.ts` reads. Declared so a typo in
 * a variable name is a type error rather than a silent `undefined` at runtime.
 * No value for any of them is committed; see `web/.env.example`.
 */
interface ImportMetaEnv {
  readonly VITE_VECTIS_API_BASE_URL?: string;
  readonly VITE_VECTIS_WORKSPACE_KEY?: string;
  readonly VITE_VECTIS_API_TIMEOUT_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
