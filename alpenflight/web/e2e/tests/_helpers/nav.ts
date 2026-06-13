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
  '/users',
  '/accountingrules',
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

  // Nested masterdata path: the Masterdata group parent must be opened first.
  // On mobile the group lives inside the burger drawer — open the drawer if the
  // group trigger isn't already on-screen (desktop renders it inline).
  const group = page.getByTestId('af-nav-group-masterdata');
  if ((await group.count()) === 0) {
    const burger = page.getByTestId('af-nav-burger');
    await expect(burger, 'mobile burger reveals the nav (Masterdata group)').toBeVisible();
    await burger.click();
  }
  await expect(group, 'the Masterdata group trigger is chrome-reachable').toBeVisible();
  await group.click();

  const child = page.getByTestId(`af-nav-section-${path}`);
  await expect(
    child,
    `the ${path} masterdata item is reachable once Masterdata is open`,
  ).toBeVisible();
  await child.click();
}
