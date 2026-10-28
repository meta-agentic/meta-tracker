import { defineConfig, devices } from '@playwright/test';

// The journeys expect Vectis and the provider to be running already; .github/workflows/e2e.yml
// starts both. VECTIS_URL and OIDC_ISSUER point elsewhere for a local run. The boards journey
// also needs its workspace stored and the web client built for it: see e2e/seed/boards.sql.
export default defineConfig({
  testDir: './tests',
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
