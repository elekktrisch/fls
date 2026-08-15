import { expect, type Page } from '@playwright/test';

const MASTERDATA_PATHS: ReadonlySet<string> = new Set([
  '/aircraft',
  '/locations',
  '/persons',
  '/flight-types',
  '/join-requests',
  '/users',
  '/accountingrules',
  '/deliverycreationtests',
  '/deliveries',
  '/email-templates',
  '/system/logs',
]);

export function isMasterdataPath(path: string): boolean {
  return MASTERDATA_PATHS.has(path);
}

export async function enterViaNav(page: Page, path: string): Promise<void> {
  if (!isMasterdataPath(path)) {
    const section = page.getByTestId(`af-nav-section-${path}`);
    await expect(section, `the ${path} nav section is chrome-reachable`).toBeVisible();
    await section.click();
    return;
  }

  await openMasterdataGroup(page);

  const child = page.getByTestId(`af-nav-section-${path}`);
  await expect(
    child,
    `the ${path} masterdata item is reachable once Masterdata is open`,
  ).toBeVisible();
  await child.click();
}

export async function openMasterdataGroup(page: Page): Promise<void> {
  const burger = page.getByTestId('af-nav-burger');
  const group = page.getByTestId('af-nav-group-masterdata');
  await expect(
    burger.or(group).first(),
    'the nav settled (Masterdata group trigger on desktop, or the burger on mobile)',
  ).toBeVisible();

  if (await burger.isVisible()) {
    await burger.click();
  }
  await expect(group, 'the Masterdata group trigger is chrome-reachable').toBeVisible();
  await group.click();
}

export const CLUB_SETTINGS_NAV_TESTID = 'af-nav-section-club-settings';

export async function enterClubSettingsViaNav(page: Page): Promise<string> {
  await openMasterdataGroup(page);

  const entry = page.getByTestId(CLUB_SETTINGS_NAV_TESTID);
  await expect(entry, 'the own-club settings entry is chrome-reachable').toBeVisible();
  const href = (await entry.getAttribute('href')) ?? '';
  const clubId = href.match(/\/clubs\/([^/]+)\/edit$/)?.[1] ?? '';
  expect(clubId, `the club-settings entry links to a club (href: ${href})`).not.toBe('');

  await entry.click();
  return clubId;
}
