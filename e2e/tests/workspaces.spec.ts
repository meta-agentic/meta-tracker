// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Page } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';

// Choosing the workspace at run time, against the real server on the database holding
// e2e/seed/boards.sql, e2e/seed/filter.sql and e2e/seed/workspaces.sql. The client is built with
// E2E as its default workspace, which is what an address without ?ws= opens.

function columns(page: Page) {
  return page.locator('section.vec-column h2');
}

test('harness: the server lists both seeded workspaces', async ({ request }) => {
  const listed = await request.get(`${vectis}/api/v1/workspaces`);
  expect(listed.status()).toBe(200);
  const keys = ((await listed.json()) as { key: string }[]).map((workspace) => workspace.key);
  expect(keys).toEqual(expect.arrayContaining(['E2E', 'TWO']));
});

test('workspace journey: switch from the shell, reload, and go back to the filtered board', async ({ page }) => {
  await page.goto(`${vectis}/`);
  const count = page.getByTestId('filter-count');
  await expect(columns(page)).toHaveText(['To Do', 'In Progress', 'Done']);

  // Narrow E2E's board first, so the switch can show the filter stays behind with E2E.
  await page.locator('summary', { hasText: 'Epic' }).click();
  await page.getByRole('checkbox', { name: /E2E-501/ }).check();
  await expect(page).toHaveURL(/[?&]epic=E2E-501(&|$)/);
  await expect(count).toContainText(/^5 of \d+ items/);

  await page.getByRole('banner').locator('summary').click();
  const menu = page.getByRole('navigation', { name: 'Workspaces on this server' });
  await expect(menu.getByRole('link', { name: /Boards journey/ })).toHaveAttribute('aria-current', 'page');
  await menu.getByRole('link', { name: /Second workspace/ }).click();

  await expect(page).toHaveURL(/[?&]ws=TWO(&|$)/);
  await expect(page).not.toHaveURL(/epic=/);
  await expect(columns(page)).toHaveText(['Queued', 'Shipped']);
  await expect(page.getByRole('button', { name: /Rotate the keys/ })).toBeVisible();
  await expect(count).toContainText('2 of 2 items');
  await expect(page.getByRole('banner')).toContainText('Second workspace');

  // A reload opens the workspace the address names, not the build-time default.
  await page.reload();
  await expect(columns(page)).toHaveText(['Queued', 'Shipped']);
  await expect(page.getByRole('banner')).toContainText('TWO');

  // Back returns to E2E, with the filter it had.
  await page.goBack();
  await expect(page).toHaveURL(/[?&]epic=E2E-501(&|$)/);
  await expect(page).not.toHaveURL(/ws=TWO/);
  await expect(columns(page)).toHaveText(['To Do', 'In Progress', 'Done']);
  await expect(count).toContainText(/^5 of \d+ items/);
});

test('an address naming an unknown workspace shows it as not found, beside the picker', async ({ page }) => {
  await page.goto(`${vectis}/?ws=NOPE`);

  await expect(page.getByRole('alert')).toContainText('Workspace NOPE was not found');
  const picker = page.getByRole('main').getByRole('navigation', { name: 'Workspaces on this server' });
  await picker.getByRole('link', { name: /Second workspace/ }).click();

  await expect(page).toHaveURL(/[?&]ws=TWO(&|$)/);
  await expect(page.getByRole('button', { name: /Rotate the keys/ })).toBeVisible();
});
