import { expect, type Page } from '@playwright/test';

/**
 * Nav chrome entry helper (J-8 T-22a).
 *
 * The masterdata screens moved UNDER a "Masterdata" group in the primary nav
 * (operator grouping — desktop `nz-dropdown`, mobile nested drawer block). The
 * group parent carries `data-testid="af-nav-group-masterdata"`; each child keeps
 * its stable `af-nav-section-<path>` testid (T-21 + the 6 consuming specs depend
 * on these). Specs that previously clicked `af-nav-section-<path>` directly for a
 * masterdata path must now open the parent group FIRST — that is exactly what
 * this helper encapsulates so the call site stays a one-liner.
 *
 * Top-level (Flights / Reports / Reservations / Planning) and the sysadmin Clubs
 * entry are NOT grouped → `enterViaNav` clicks them directly.
 *
 * Robust for both viewports: on desktop the group opens an nz-dropdown overlay;
 * on mobile the burger drawer must be open first and the group is an expandable
 * block. The helper detects which surface is live and drives it accordingly. No
 * `waitForTimeout` — every step gates on app/DOM visibility.
 *
 * `path` is the FULL route path with leading slash, exactly as the testid encodes
 * it: `/persons`, `/accountingrules`, `/flight-types`, `/users`, etc.
 */

/** Masterdata-group children (paths nested behind `af-nav-group-masterdata`). */
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

/** `true` when `path` lives under the Masterdata group (i.e. needs a parent open). */
export function isMasterdataPath(path: string): boolean {
  return MASTERDATA_PATHS.has(path);
}

/**
 * Enter a screen the way an operator does — through the nav chrome. For a nested
 * masterdata path, open the Masterdata group first (dropdown on desktop, drawer
 * block on mobile), then click the child; otherwise click the top-level section
 * directly.
 *
 * Assumes the nav-bar is already rendered (the caller has landed on an
 * app-shell route, e.g. `/start`). Does NOT itself navigate to the shell.
 */
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

/**
 * Expand the Masterdata group so its children are clickable.
 *
 * The nav renders exactly ONE surface (af-nav-bar `@if (!isWide())` burger vs
 * `@if (isWide())` inline tabs) and that surface may still be mounting when we
 * arrive — so DON'T branch on a point-in-time `count()` (it races the render and
 * wrongly takes the burger path on desktop). Instead wait for the live surface to
 * settle: the desktop group trigger OR the mobile burger, whichever this viewport
 * paints, is visible before we decide.
 */
export async function openMasterdataGroup(page: Page): Promise<void> {
  const burger = page.getByTestId('af-nav-burger');
  const group = page.getByTestId('af-nav-group-masterdata');
  await expect(
    burger.or(group).first(),
    'the nav settled (Masterdata group trigger on desktop, or the burger on mobile)',
  ).toBeVisible();

  // On mobile the group lives inside the burger drawer — open it first. On
  // desktop the burger is absent (`@if (!isWide())`), so this is skipped.
  if (await burger.isVisible()) {
    await burger.click();
  }
  await expect(group, 'the Masterdata group trigger is chrome-reachable').toBeVisible();
  await group.click();
}

/** Testid of the own-club settings entry (its path carries the club id). */
export const CLUB_SETTINGS_NAV_TESTID = 'af-nav-section-club-settings';

/**
 * Enter the club-edit screen the way a club administrator does — through the
 * Masterdata group's own-club entry. That role cannot read the club catalog, so
 * this entry is its only chrome route to the screen. Returns the club id the
 * entry links to, so the caller can assert it is the caller's OWN club.
 */
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
