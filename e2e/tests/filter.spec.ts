import { expect, test } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';

// VEC-114: the board filter against the real server on the database holding e2e/seed/boards.sql
// and e2e/seed/filter.sql, with the web client built for the E2E workspace.

test('filter journey: an epic narrows the board, and the filter survives a reload and a tab switch', async ({ page }) => {
  await page.goto(`${vectis}/`);
  const count = page.getByTestId('filter-count');
  await expect(count).toContainText(/^\d+ of \d+ items/);
  // Whatever the board holds: the journey reads the total instead of assuming it.
  const total = /^(\d+) of (\d+) items/.exec((await count.textContent()) ?? '')?.[2];
  expect(total).toBeTruthy();
  await expect(count).toContainText(`${total} of ${total} items`);

  await page.locator('summary', { hasText: 'Epic' }).click();
  await page.getByRole('checkbox', { name: /E2E-501/ }).check();
  await expect(count).toContainText(new RegExp(`^5 of ${total} items`));
  await expect(page).toHaveURL(/[?&]epic=E2E-501(&|$)/);
  await expect(page.getByRole('button', { name: /Seeded item 4\b/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Seeded item 9\b/ })).toHaveCount(0);

  // A reload keeps it.
  await page.reload();
  await expect(count).toContainText(new RegExp(`^5 of ${total} items`));

  // So does a tab switch, there and back.
  // The Backlog also lists items in columns kept off the board, so its total is its own.
  await page.getByRole('button', { name: 'Backlog', exact: true }).click();
  await expect(count).toContainText(/^5 of \d+ items/);
  await page.getByRole('button', { name: 'Boards', exact: true }).click();
  await expect(count).toContainText(new RegExp(`^5 of ${total} items`));

  // One control clears it.
  await page.getByRole('button', { name: 'Clear filter' }).click();
  await expect(count).toContainText(`${total} of ${total} items`);
  await expect(page).not.toHaveURL(/epic=/);
});
