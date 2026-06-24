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
  // The nav renders exactly ONE surface (af-nav-bar `@if (!isWide())` burger vs
  // `@if (isWide())` inline tabs) and that surface may still be mounting when we
  // arrive — so DON'T branch on a point-in-time `count()` (it races the render
  // and wrongly takes the burger path on desktop). Instead wait for the live
  // surface to settle: the desktop group trigger OR the mobile burger, whichever
  // this viewport paints, is visible before we decide.
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

  const child = page.getByTestId(`af-nav-section-${path}`);
  await expect(
    child,
    `the ${path} masterdata item is reachable once Masterdata is open`,
  ).toBeVisible();
  await child.click();
}
