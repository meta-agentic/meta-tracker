import { expect, test, type Page } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';

// VEC-107: the Boards tab against the real server on a database holding e2e/seed/boards.sql,
// with the web client built for the E2E workspace (see that file).

function column(page: Page, name: string) {
  return page.locator('section.vec-column', { has: page.getByRole('heading', { name, exact: true }) });
}

test('harness: the seeded workspace is stored', async ({ request }) => {
  const workspace = await request.get(`${vectis}/api/v1/workspaces/E2E`);
  expect(workspace.status()).toBe(200);
  expect((await workspace.json()).key).toBe('E2E');
});

test('boards journey: after a reload the Boards tab shows the stored columns and items', async ({ page }) => {
  await page.goto(`${vectis}/`);
  await expect(page.getByRole('heading', { name: 'To Do', exact: true })).toBeVisible();

  // The second load paints from the cache and then revalidates against the server.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'To Do', exact: true })).toBeVisible();

  // The stored columns in ordinal order; the one kept off the board is not shown.
  await expect(page.locator('section.vec-column h2')).toHaveText(['To Do', 'In Progress', 'Done']);
  await expect(page.getByRole('heading', { name: 'Icebox' })).toHaveCount(0);

  // In Progress holds its two items in rank order.
  const doing = column(page, 'In Progress');
  await expect(doing.locator('.vec-count')).toHaveText('2');
  await expect(doing.getByRole('button')).toHaveText([/Follow the cursor/, /Render the board from the server/]);
  await expect(column(page, 'Done').getByText('No items')).toBeVisible();

  // To Do holds more items than one page. Its first card by rank is its oldest item,
  // which only the second page carries.
  const todo = column(page, 'To Do');
  await expect(todo.locator('.vec-count')).toHaveText('205');
  await expect(todo.getByRole('button').first()).toContainText('Seeded item 1');
  await expect(todo.getByText('+155 more')).toBeVisible();
});

test('an unknown workspace shows the server problem, not a crash', async ({ page }) => {
  // The client is built for E2E; send its reads to a workspace the server does not have.
  await page.route('**/api/v1/workspaces/E2E**', (route) =>
    route.continue({ url: route.request().url().replace('/workspaces/E2E', '/workspaces/NOPE') }),
  );
  await page.goto(`${vectis}/`);

  const alert = page.getByRole('alert');
  await expect(alert).toContainText('The workspace could not be loaded.');
  await expect(alert).toContainText('workspace NOPE does not exist');
  await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);
  await expect(page.getByRole('banner')).toBeVisible();
});
