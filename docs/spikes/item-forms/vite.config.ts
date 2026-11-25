// THROWAWAY PROTOTYPE (VEC-67 spike). Borrows web/node_modules through the
// symlink ./run.sh makes; nothing here is installed on its own.
/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { port: 5299, fs: { allow: ["../../.."] } },
  test: {
    environment: "jsdom",
    include: ["*.test.tsx"],
    setupFiles: ["./setup.ts"],
  },
});
