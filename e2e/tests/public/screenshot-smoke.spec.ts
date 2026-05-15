import { test, screenshot } from '../../fixtures';

const PUBLIC_ROUTES: { name: string; path: string }[] = [
  { name: 'main-anonymous',  path: '#/main' },
  { name: 'lostpassword',    path: '#/lostpassword' },
  { name: 'trialflight',     path: '#/trialflight' },
  { name: 'passengerflight', path: '#/passengerflight' },
];

for (const { name, path } of PUBLIC_ROUTES) {
  test(`public:${name}`, async ({ page }) => {
    await page.goto('/' + path);
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(400);
    await screenshot(page, name);
  });
}
