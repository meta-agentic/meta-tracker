// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Page } from '@playwright/test';

const vectis = process.env.VECTIS_URL ?? 'http://localhost:8080';

// Item forms drawn from the workspace configuration (VEC-70), against the real server on the
// database holding e2e/seed/forms.sql: FRM on the built-in scrum@1, GATE on stage-gate@1. The
// journeys change their workspace, so re-seed before a second run.

function column(page: Page, name: string) {
  return page.locator('section.vec-column').filter({ has: page.getByRole('heading', { name, exact: true }) });
}

async function openCard(page: Page, title: string) {
  await page.getByRole('button', { name: new RegExp(title) }).first().click();
  return page.getByRole('dialog');
}

test('scrum: create a story with points and priority, then edit its points', async ({ page }) => {
  const title = `Sign in with a passkey ${Date.now()}`;
  await page.goto(`${vectis}/?ws=FRM`);
  await page.getByRole('button', { name: 'New item' }).click();
  const sheet = page.getByRole('dialog', { name: 'New item' });
  await expect(sheet.getByRole('radio', { name: 'Story' })).toBeChecked();
  await sheet.getByLabel(/^Title/).fill(title);
  await sheet.getByRole('combobox', { name: /^Story points/ }).click();
  await sheet.getByRole('option', { name: '5', exact: true }).click();
  await sheet.getByRole('radio', { name: 'High' }).check();
  await sheet.getByRole('button', { name: 'Create' }).click();
  await expect(sheet).toBeHidden();
  await expect(column(page, 'Backlog').getByRole('button', { name: new RegExp(title) })).toBeVisible();

  const detail = await openCard(page, title);
  await detail.getByRole('button', { name: 'Edit' }).click();
  await expect(detail.getByRole('combobox', { name: /^Story points/ })).toHaveText('5');
  await detail.getByRole('combobox', { name: /^Story points/ }).click();
  await detail.getByRole('option', { name: '8', exact: true }).click();
  await detail.getByRole('button', { name: 'Save' }).click();
  await expect(detail.getByRole('button', { name: 'Edit' })).toBeVisible();
  const read = await page.request.get(`${vectis}/api/v1/workspaces/FRM/items?limit=200`);
  const created = ((await read.json()) as { items: { title: string; fields: Record<string, unknown>; version: number }[] })
    .items.find((item) => item.title === title)!;
  expect(created.fields).toMatchObject({ type: 'story', storyPoints: 8, priority: 'high' });
  expect(created.version).toBe(2);
});

test('scrum: a bug without a description is refused on its field, in English and in Italian', async ({ page }) => {
  await page.goto(`${vectis}/?ws=FRM`);
  await page.getByRole('button', { name: 'New item' }).click();
  let sheet = page.getByRole('dialog', { name: 'New item' });
  await sheet.getByRole('radio', { name: 'Bug' }).check();
  await sheet.getByLabel(/^Title/).fill('Crash on start');
  await sheet.getByRole('button', { name: 'Create' }).click();
  await expect(sheet.getByLabel(/^Description/)).toHaveAttribute('aria-invalid', 'true');
  await expect(sheet.getByLabel(/^Description/)).toHaveAccessibleDescription('Description is required.');
  await expect(sheet.getByLabel(/^Description/)).toBeFocused();
  await sheet.getByRole('button', { name: 'Cancel' }).click();

  await page.getByLabel('Language').selectOption('it');
  await page.getByRole('button', { name: 'Nuovo elemento' }).click();
  sheet = page.getByRole('dialog', { name: 'Nuovo elemento' });
  await sheet.getByRole('radio', { name: 'Bug' }).check();
  await sheet.getByLabel(/^Titolo/).fill('Crash on start');
  await sheet.getByRole('button', { name: 'Crea' }).click();
  await expect(sheet.getByLabel(/^Description/)).toHaveAccessibleDescription('Description è obbligatorio.');
  await sheet.getByRole('button', { name: 'Annulla' }).click();
  await page.getByLabel('Lingua').selectOption('en');
});

test('stage-gate: Scoping to Gate 1 prompts for the business case and moves with it', async ({ page }) => {
  await page.goto(`${vectis}/?ws=GATE`);
  const detail = await openCard(page, 'Enter the Nordic market');
  await detail.getByLabel('Move to').selectOption({ label: 'Gate 1: go to development' });
  await detail.getByRole('button', { name: 'Move', exact: true }).click();
  const prompt = detail.getByRole('region', { name: /Gate 1: go to development needs more/ });
  await expect(prompt.getByRole('textbox')).toHaveCount(1);
  await prompt.getByLabel(/^Business case/).fill('A market worth entering');
  await prompt.getByRole('button', { name: 'Move to Gate 1: go to development' }).click();
  await expect(prompt).toBeHidden();
  await detail.getByRole('button', { name: 'Close' }).click();
  await expect(column(page, 'Gate 1: go to development').getByRole('button', { name: /Enter the Nordic market/ })).toBeVisible();
  const item = await (await page.request.get(`${vectis}/api/v1/workspaces/GATE/items/GATE-1`)).json();
  expect(item).toMatchObject({ column: 'gate-1', version: 2, fields: { businessCase: 'A market worth entering' } });
});

test('stage-gate: Idea to Scoping does not prompt', async ({ page }) => {
  await page.goto(`${vectis}/?ws=GATE`);
  const detail = await openCard(page, 'Second product line');
  await detail.getByLabel('Move to').selectOption({ label: 'Scoping' });
  await detail.getByRole('button', { name: 'Move', exact: true }).click();
  await expect(detail.getByRole('region', { name: /needs more/ })).toHaveCount(0);
  await detail.getByRole('button', { name: 'Close' }).click();
  await expect(column(page, 'Scoping').getByRole('button', { name: /Second product line/ })).toBeVisible();
});

test('stage-gate: a milestone, which has no business case, enters Gate 1 without a prompt', async ({ page }) => {
  await page.goto(`${vectis}/?ws=GATE`);
  const detail = await openCard(page, 'Pilot in one city');
  await detail.getByLabel('Move to').selectOption({ label: 'Gate 1: go to development' });
  await detail.getByRole('button', { name: 'Move', exact: true }).click();
  await expect(detail.getByRole('region', { name: /needs more/ })).toHaveCount(0);
  await detail.getByRole('button', { name: 'Close' }).click();
  await expect(column(page, 'Gate 1: go to development').getByRole('button', { name: /Pilot in one city/ })).toBeVisible();
});

test('a workspace imported from its source shows no write control, and says why', async ({ page }) => {
  await page.goto(`${vectis}/?ws=RO`);
  await expect(page.getByRole('note')).toContainText('read-only');
  await expect(page.getByRole('button', { name: 'New item' })).toHaveCount(0);
  const detail = await openCard(page, 'Changed at the source');
  await expect(detail.getByRole('note')).toContainText('read-only');
  await expect(detail.getByRole('button', { name: 'Edit' })).toHaveCount(0);
  await expect(detail.getByLabel('Move to')).toHaveCount(0);
});
