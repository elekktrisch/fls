import { test } from '@playwright/test';

const PUBLIC_ROUTES: { name: string; path: string }[] = [
  { name: 'main-anonymous',  path: '#/main' },
  { name: 'lostpassword',    path: '#/lostpassword' },
  { name: 'trialflight',     path: '#/trialflight' },
  { name: 'passengerflight', path: '#/passengerflight' },
];

for (const { name, path } of PUBLIC_ROUTES) {
  test(`public:${name}`, async ({ page }) => {
    await page.goto('/' + path);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(400);
    await page.screenshot({ path: `screenshots/public-${name}.png`, fullPage: true });
  });
}
