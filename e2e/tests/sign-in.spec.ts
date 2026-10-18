import { expect, test } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';
const issuer = process.env.OIDC_ISSUER ?? 'http://localhost:8180/realms/vectis';

// Must pass: without it, the expected failure below could hide a harness that never started.
test('harness: the provider publishes its discovery document and Vectis answers', async ({ request }) => {
  const discovery = await request.get(`${issuer}/.well-known/openid-configuration`);
  expect(discovery.ok()).toBe(true);
  expect((await discovery.json()).issuer).toBe(issuer);
  expect((await request.get(`${vectis}/api/extensions`)).status()).toBeLessThan(500);
});

test('sign-in journey: open Vectis, sign in, land on the board with no token in the page', async ({ page }) => {
  // Expected-pending: runs on every build and is reported as an expected failure until
  // VEC-35 ships sign-in. Once the journey passes, Playwright fails it, and VEC-35 removes this line.
  test.fail(true, 'pending VEC-35 (sign-in)');

  await page.goto(`${vectis}/`);
  await expect(page).toHaveURL(/\/signin/);
  await page.getByLabel(/username/i).fill('alice');
  await page.getByLabel(/password/i).fill('alice-test-password');
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).toHaveURL(new RegExp(`^${vectis}/`));
  await expect(page.getByRole('main')).toBeVisible();

  // ADR-VEC-02: no token is readable by script.
  expect(await page.evaluate(() => document.cookie)).toBe('');
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
});
