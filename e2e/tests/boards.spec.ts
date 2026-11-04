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
  await expect(column(page, 'Done').locator('.vec-count')).toHaveText('2'); // the detail journey's stories

  // To Do holds more items than one page. Its first card by rank is its oldest item,
  // which only the second page carries.
  const todo = column(page, 'To Do');
  await expect(todo.locator('.vec-count')).toHaveText('205');
  await expect(todo.getByRole('button').first()).toContainText('Seeded item 1');
  await expect(todo.getByText('+155 more')).toBeVisible();
});

test('item detail journey: a story shows its epic chain and the items that depend on it', async ({ page }) => {
  await page.goto(`${vectis}/`);
  await column(page, 'Done').getByRole('button', { name: /Story under the epics/ }).click();

  // The epic's epic, then the epic, outermost first; the outer one is not indented.
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('.vec-ancestor .vec-key')).toHaveText(['E2E-401', 'E2E-402']);
  await expect(dialog.locator('.vec-ancestor').nth(1)).toContainText('Inner epic');
  await expect(dialog.getByText('extension +0.2')).toBeVisible();

  // An ancestor opens in the same panel, with its own parent above it and its stories below.
  await dialog.getByRole('button', { name: 'E2E-402' }).click();
  await expect(dialog.getByRole('heading', { name: 'Inner epic' })).toBeVisible();
  await expect(dialog.locator('.vec-ancestor .vec-key')).toHaveText(['E2E-401']);
  const inEpic = dialog.locator('.vec-links', { has: page.getByRole('heading', { name: 'In this epic' }) });
  await expect(inEpic.getByRole('button', { name: /E2E-403/ })).toBeVisible();
  await inEpic.getByRole('button', { name: /E2E-403/ }).click();

  // A link both ways: open the item it depends on and find the story under "Depended on by".
  await dialog.getByRole('button', { name: /E2E-404/ }).click();
  const reverse = dialog.locator('.vec-links', { has: page.getByRole('heading', { name: 'Depended on by' }) });
  await expect(reverse.getByRole('button', { name: /E2E-403/ })).toBeVisible();
  await reverse.getByRole('button', { name: /E2E-403/ }).click();
  await expect(dialog.getByRole('heading', { name: 'Story under the epics' })).toBeVisible();
});

test('item detail journey: the sprint chip shows the item\'s sprint', async ({ page }) => {
  await page.goto(`${vectis}/`);
  await column(page, 'In Progress').getByRole('button', { name: /Follow the cursor/ }).click();
  await expect(page.getByRole('dialog').locator('.vec-sprint-chip')).toHaveText('E2E-S2');
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
