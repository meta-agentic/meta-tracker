import { expect, test, type Page } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';

// VEC-112: the Sprint tab against the real server on the database holding e2e/seed/boards.sql,
// with the web client built for the E2E workspace.

function column(page: Page, name: string) {
  return page.locator('section.vec-column', { has: page.getByRole('heading', { name, exact: true }) });
}

test('sprint journey: the active sprint under its header, a closed sprint where its items ended, and a reload', async ({ page }) => {
  await page.goto(`${vectis}/`);
  await page.getByRole('button', { name: 'Sprint', exact: true }).click();

  // The header of the active sprint.
  const header = page.getByRole('region', { name: 'E2E-S2' });
  await expect(header).toBeVisible();
  await expect(header).toContainText('Active');
  await expect(header).toContainText('Show the sprint');
  await expect(header).toContainText('Oct 15, 2026 – Oct 28, 2026');
  await expect(header.locator('dd').nth(1)).toHaveText('10');
  await expect(header.locator('dd').nth(2)).toHaveText('0');

  // Its items, in the board's columns; the switcher lists the sprints newest first.
  await expect(page.locator('section.vec-column h2')).toHaveText(['To Do', 'In Progress', 'Done']);
  await expect(column(page, 'In Progress').getByRole('button')).toHaveText([/Follow the cursor/, /Render the board from the server/]);
  await expect(column(page, 'To Do').getByText('No items')).toBeVisible();
  const switcher = page.getByRole('navigation', { name: 'Sprints of this workspace' });
  await expect(switcher.getByRole('button')).toHaveText(['E2E-S3', 'E2E-S2', 'E2E-S1']);

  // Imported items are read-only, and the tab says so.
  await expect(page.getByText('Read-only: these items are imported')).toBeVisible();
  // An item held off the board is counted, not dropped.
  await expect(page.getByText('1 item is in a column that is not shown on the board.')).toBeVisible();
  // Dragging a card onto another column moves nothing.
  await column(page, 'In Progress').getByRole('button', { name: /Follow the cursor/ }).dragTo(column(page, 'Done'));
  await expect(column(page, 'In Progress').getByRole('button')).toHaveCount(2);
  await expect(column(page, 'Done').getByText('No items')).toBeVisible();

  // A closed sprint shows its items where they ended.
  await switcher.getByRole('button', { name: 'E2E-S1' }).click();
  await expect(page.getByRole('region', { name: 'E2E-S1' })).toContainText('Closed');
  await expect(column(page, 'To Do').getByRole('button')).toHaveText([/Seeded item 1/, /Seeded item 2/, /Seeded item 3/]);
  await expect(column(page, 'In Progress').getByText('No items')).toBeVisible();

  // A reload keeps the tab and the sprint.
  await page.reload();
  await expect(page.getByRole('region', { name: 'E2E-S1' })).toBeVisible();
  await expect(column(page, 'To Do').getByRole('button')).toHaveText([/Seeded item 1/, /Seeded item 2/, /Seeded item 3/]);

  // A planned sprint has no dates, and none is made up.
  await switcher.getByRole('button', { name: 'E2E-S3' }).click();
  const planned = page.getByRole('region', { name: 'E2E-S3' });
  await expect(planned).toContainText('No dates');
  await expect(page.getByText('No items in this sprint')).toBeVisible();
});
