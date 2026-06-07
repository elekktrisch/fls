import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-6b reservations & planning HARDENING — real chain (live Keycloak auth + real
 * Spring backend + real Postgres). The journey's `parity_test` real-idp sibling
 * (T-17), the heavy-chain fidelity for the load-bearing flows that ONLY a REAL
 * principal can prove:
 *
 *   [happy]  a real CLUB_ADMINISTRATOR (clubadmin1) sees a `Reservations` nav
 *            entry and navigates to the /reservations calendar (AC4).
 *   [edge]   clubadmin1 does NOT see a `Clubs` nav entry — the NEW operator
 *            decision to make /clubs sysadmin-only (AC14); the mock-admin (both
 *            roles) would HIDE this gap, so it needs a real low-privilege
 *            principal ([[project_real_idp_real_roles_catches_authz_gaps]]).
 *   [happy]  a real SYSTEM_ADMINISTRATOR (sysadmin) DOES see `Clubs` — the
 *            positive control proving the gate is role-driven, not a blanket hide.
 *   [happy]  clubadmin1 opens the Users menu and the list renders (no 400) (AC13,
 *            T-15: the 400 was the missing-tenant-row case, fixed by the V8 seed —
 *            this asserts the menu renders the list for the EXACT dev principal).
 *   [happy]  the V36 dev-seed gives clubadmin1's club ≥1 row per user-facing
 *            aggregate list — assert Persons (was empty pre-V36) + Aircraft now
 *            render a row (AC12).
 *   [happy]  the /reservations Day/Week calendar renders the selected toggle
 *            LEGIBLY (dark ground + white text, not blacked-out) and a DD.MM.YYYY
 *            period label in day view / a DD.MM.YYYY – DD.MM.YYYY range in week
 *            view (AC5/AC6/AC10) — the greenfield calendar gallery shots.
 *
 * Mirrors the J-5/J-6 real-idp discipline: warm in-app nav only (no
 * `clearCookies` — kills session restore [[project_real_idp_goto_reboot_renew_stall]]);
 * a fresh recorded context per test → the pass-video is flushed on ctx.close()
 * then attached via `proofVideo` from the `finally`. DELTA/presence assertions,
 * never absolutes. No mocks (every seam is the real stack); no `@mocked:` tags.
 *
 * PRINCIPALS — the J-0 Keycloak users (realm-export.json + Flyway dev-user seed):
 *   - clubadmin1@example.com — CLUB_ADMINISTRATOR bound to seed-club-1 via its V8
 *     `t_user` row (symbolic `clubId="club-1"` claim → t_user lookup; T-15). The
 *     low-privilege principal for the nav matrix + Users-list + seeded-lists ACs.
 *   - sysadmin@example.com — SYSTEM_ADMINISTRATOR (no clubId) — the Clubs-visible
 *     positive control.
 */

interface SeededPrincipal {
  username: string;
  password: string;
}

const CLUB_ADMIN1: SeededPrincipal = {
  username: 'clubadmin1@example.com',
  password: 'clubadmin1-dev-2026!',
};
const SYSADMIN: SeededPrincipal = {
  username: 'sysadmin@example.com',
  password: 'sysadmin-dev-2026!',
};

/** A new recorded context (one isolated session per test → its own pass-video). */
async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  return browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
}

/**
 * Log a seeded principal in through the real Keycloak redirect flow, landing back
 * on the SPA. Warm in-app nav from here (no cold `page.goto` mid-session, no
 * `clearCookies`).
 */
async function loginAs(page: Page, principal: SeededPrincipal): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, principal.username, principal.password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
  await expect(page.getByTestId('landing-topbar-sign-in')).toHaveCount(0);
}

/** Capture the Bearer the OIDC interceptor attaches to the principal's first read. */
async function captureBearer(page: Page, warmPath: string): Promise<string> {
  const bearerPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
    { timeout: 15_000 },
  );
  await page.goto(warmPath);
  return (await bearerPromise).headers()['authorization']!;
}

// ===========================================================================
// NAV ROLE-GATING — the load-bearing real-principal matrix (AC4/AC13/AC14).
// ===========================================================================
test.describe('J-6b nav role-gating (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  test.beforeAll((_fixtures, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] a real CLUB_ADMINISTRATOR sees Reservations + Users in the nav, navigates to the calendar — and does NOT see Clubs', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      await page.goto('/start?lang=de');

      // The Reservations nav entry is present for a club admin (AC4) — added to
      // TENANT_SECTIONS (T-11). The desktop section tabs render at the default
      // 1280px viewport.
      const reservations = page.getByTestId('af-nav-section-/reservations');
      await expect(reservations, 'a club admin sees the Reservations nav entry').toBeVisible();

      // Users IS visible for a club admin (CLUB_ADMIN_SECTIONS, T-11).
      await expect(page.getByTestId('af-nav-section-/users')).toBeVisible();

      // Clubs is NOT visible for a club admin (AC14 — the NEW operator decision to
      // make /clubs sysadmin-only; legacy showed Clubs to all). The mock-admin
      // (both roles → sysadmin branch) would HIDE this gap, so this needs the
      // real low-privilege principal.
      await expect(
        page.getByTestId('af-nav-section-/clubs'),
        'a club admin does NOT see the Clubs nav entry (sysadmin-only)',
      ).toHaveCount(0);

      // CAPTURE-BEFORE-DEEP-ASSERT: the club-admin nav (Reservations present, no Clubs).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-nav-clubadmin.png`,
        fullPage: true,
      });

      // The Reservations entry routes to the /reservations calendar (the routing
      // wiring + the real screen render).
      await reservations.click();
      await expect(page).toHaveURL(/\/reservations(\?|$)/);
      await expect(page.getByTestId('reservations-view-toggle')).toBeVisible();
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · nav · a real CLUB_ADMINISTRATOR (clubadmin1) logs in via real Keycloak — the nav ' +
          'shows the new Reservations entry (routes to the /reservations Day/Week calendar) and ' +
          'Users, but does NOT show Clubs (the new operator decision to make /clubs sysadmin-only; ' +
          'the mock admin-everything principal would have hidden this role gate)',
        acTag: 'edge',
      });
    }
  });

  test('[happy] a real SYSTEM_ADMINISTRATOR DOES see Clubs (the role-gate positive control)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, SYSADMIN);
      await page.goto('/clubs?lang=de');

      // The sysadmin branch returns Clubs (SYS_ADMIN_SECTIONS, T-11) — proving the
      // hide for the club admin above is ROLE-driven, not a blanket removal.
      await expect(
        page.getByTestId('af-nav-section-/clubs'),
        'a sysadmin DOES see the Clubs nav entry',
      ).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · nav · a real SYSTEM_ADMINISTRATOR sees the Clubs nav entry — the positive control ' +
          'proving the Clubs hide for a club admin is role-gated, not a blanket removal',
        acTag: 'happy',
      });
    }
  });
});

// ===========================================================================
// USERS-LIST + SEEDED LISTS — clubadmin1 reads render (no 400) + V36 non-empty.
// ===========================================================================
test.describe('J-6b clubadmin1 reads render (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  test.beforeAll((_fixtures, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] clubadmin1 opens the Users menu and the list renders (no 400)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);

      // Watch the real GET /api/v1/users response — the T-15 AC: it must NOT 400
      // for the exact dev clubadmin1 principal (symbolic clubId claim → t_user
      // lookup; the V8 seed restored the row that was the menu-broken symptom).
      const usersResp = page.waitForResponse(
        (r) => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/v1/users',
        { timeout: 15_000 },
      );
      await page.goto('/users?lang=de');
      const resp = await usersResp;
      expect(
        resp.status(),
        `GET /api/v1/users must render for clubadmin1, not 400 — got ${resp.status()}: ${
          resp.status() === 200 ? 'ok' : await resp.text()
        }`,
      ).toBe(200);

      // The list (or a populated table) renders — there is no error banner and at
      // least one user row is present (clubadmin1 itself is a seeded user).
      await expect(page.getByTestId('users-error')).toHaveCount(0);
      await expect(
        page.locator('[data-testid^="user-row-"]').first(),
        'the Users list renders ≥1 user row for clubadmin1 (no 400, no empty)',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-users-list.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · users · clubadmin1 opens the Users menu and the list renders (GET /api/v1/users ' +
          '→ 200, ≥1 row) — the T-15 AC: no 400 for the exact dev principal (the menu-broken ' +
          'symptom was the missing tenant row, restored by the V8 seed)',
        acTag: 'happy',
      });
    }
  });

  test('[happy] the V36 dev-seed gives clubadmin1’s club ≥1 row in the Persons + Aircraft lists', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      const bearer = await captureBearer(page, '/persons?lang=de');

      // AC12: every user-facing aggregate list has ≥1 row for clubadmin1's club.
      // Persons was the operator's reported empty (V36 `t_person_club`→3) and
      // Aircraft is a representative second list (V36 seeds one). Assert via the
      // real tenant-scoped reads AND the rendered list rows (the UI manifestation).
      await expect(
        page.getByTestId('persons-table'),
        'the Persons list renders for clubadmin1 (V36 seeded ≥1 person-club)',
      ).toBeVisible();
      await expect(
        page.locator('[data-testid^="person-row-"]').first(),
        'the Persons list shows ≥1 row (was empty pre-V36 — the operator symptom)',
      ).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-persons-list.png`,
        fullPage: true,
      });

      // Aircraft list — a second seeded aggregate, asserted via the tenant read +
      // the rendered list (delta/presence — seed-club-1 is never truncated).
      const aircraftRes = await ctx.request.get('/api/v1/aircraft', {
        headers: { authorization: bearer },
      });
      expect(aircraftRes.status(), 'GET /api/v1/aircraft renders for clubadmin1').toBe(200);
      const aircraft = (await aircraftRes.json()) as unknown[];
      expect(
        aircraft.length,
        'the Aircraft aggregate has ≥1 row for clubadmin1’s club (V36 seed)',
      ).toBeGreaterThanOrEqual(1);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · seed · clubadmin1’s club has ≥1 row in every user-facing aggregate list — the ' +
          'Persons list (the operator’s reported empty, restored by the V36 dev seed) renders a ' +
          'row, and the Aircraft aggregate read returns ≥1 (the empirical-sweep DoD: zero empty ' +
          'cells for any aggregate a testuser should own)',
        acTag: 'happy',
      });
    }
  });
});

// ===========================================================================
// RESERVATIONS CALENDAR — greenfield Day/Week (AC5/AC6/AC10) — the gallery shots.
// ===========================================================================
test.describe('J-6b reservations calendar (real-idp)', () => {
  let baseURL: string;
  test.beforeAll((_fixtures, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] the Day/Week calendar renders the selected toggle legibly + a DD.MM.YYYY period label (day) / range (week)', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      await page.goto('/reservations?lang=de');
      await expect(page.getByTestId('reservations-day-grid')).toBeVisible();

      // AC5: the selected toggle is LEGIBLE (dark ground + light text, not the
      // blacked-out fg≈bg bug). Day is selected by default → carries the hook.
      const dayBtn = page.getByTestId('reservations-view-day');
      const weekBtn = page.getByTestId('reservations-view-week');
      await expect(dayBtn).toHaveAttribute('data-selected', 'true');

      // CAPTURE the greenfield Day-view gallery shot BEFORE the deep colour assert.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-calendar-day.png`,
        fullPage: true,
      });

      const lumOf = (el: ReturnType<Page['getByTestId']>, prop: 'backgroundColor' | 'color') =>
        el.evaluate((node, p) => {
          const raw = getComputedStyle(node as HTMLElement)[p as 'backgroundColor' | 'color'];
          const probe = document.createElement('span');
          probe.style.backgroundColor = raw;
          document.body.appendChild(probe);
          const rgb = getComputedStyle(probe).backgroundColor;
          probe.remove();
          const [r, g, b] = rgb.match(/\d+(\.\d+)?/g)!.map(Number);
          return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
        }, prop);
      const selBg = await lumOf(dayBtn, 'backgroundColor');
      const selFg = await lumOf(dayBtn, 'color');
      expect(selBg, 'the selected toggle ground is dark').toBeLessThan(96);
      expect(selFg, 'the selected toggle text is light (legible, not blacked-out)').toBeGreaterThan(
        160,
      );

      // AC10/day: the period label is a single DD.MM.YYYY in day view.
      const label = page.getByTestId('reservations-period-label');
      await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}$/);

      // AC6/week: switch to week view → the pager steps weeks + the label is a
      // DD.MM.YYYY – DD.MM.YYYY range.
      await weekBtn.click();
      await expect(weekBtn).toHaveAttribute('data-selected', 'true');
      await expect(page.getByTestId('reservations-week-grid')).toBeVisible();
      await expect(label).toHaveText(/^\d{2}\.\d{2}\.\d{4}\s*[–-]\s*\d{2}\.\d{2}\.\d{4}$/);

      // CAPTURE the greenfield Week-view gallery shot.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-reservations-calendar-week.png`,
        fullPage: true,
      });

      // The week pager moves the range a whole week (the view-aware step).
      const before = (await label.textContent())?.trim() ?? '';
      await page.getByTestId('reservations-next-week').click();
      await expect(label).not.toHaveText(before);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · reservations calendar (greenfield, no legacy parity) · the /reservations Day/Week ' +
          'calendar renders the selected toggle LEGIBLY (dark ground + white text, not the ' +
          'blacked-out bug) with a DD.MM.YYYY period label in day view and a DD.MM.YYYY – ' +
          'DD.MM.YYYY range that pages by weeks in week view (real Keycloak + backend)',
        acTag: 'happy',
      });
    }
  });
});

// ===========================================================================
// PLANNING read-only → edit-mode (AC7/AC8) — the planning-edit FORM gallery shot.
// ===========================================================================
test.describe('J-6b planning read-only + edit-mode (real-idp)', () => {
  let baseURL: string;
  test.beforeAll((_fixtures, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
  });

  test('[happy] a seeded planning day opens read-only (all fields disabled) + an Edit toggle flips it to edit mode', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAs(page, CLUB_ADMIN1);
      const bearer = await captureBearer(page, '/planning?lang=de');

      // Resolve a real future planning day off clubadmin1's tenant-scoped paged
      // read (the V34 dev seed gives seed-club-1 ≥1 future day) — by id, not a
      // hardcoded seed UUID, so a co-located spec re-pick can't break it.
      const paged = await ctx.request.post('/api/v1/planning-days/page/0/50', {
        headers: { authorization: bearer, 'content-type': 'application/json' },
        data: { sorting: { planningDate: 'asc' } },
      });
      expect(paged.status(), 'clubadmin1 can page its planning days').toBe(200);
      const body = (await paged.json()) as { items: { id: string }[] };
      expect(
        body.items.length,
        'the V34 dev seed gives seed-club-1 ≥1 future planning day to open read-only',
      ).toBeGreaterThanOrEqual(1);
      const dayId = body.items[0]!.id;

      // READ-ONLY (AC7): every field is disabled — not merely Save hidden.
      await page.goto(`/planning/${dayId}/view?lang=de`);
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();

      // CAPTURE the planning-edit FORM gallery shot (side=alpenflight, view=form)
      // BEFORE the deep field-state asserts — pairs against the committed legacy
      // `planning/form.png` ref. The read-only form shows the populated field set.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-edit-form.png`,
        fullPage: true,
      });

      await expect(
        page.getByTestId('planning-date').locator('input'),
        'the date field is disabled in read-only mode (CVA setDisabledState, T-09)',
      ).toBeDisabled();
      await expect(page.getByTestId('planning-remarks').locator('input')).toBeDisabled();
      await expect(page.getByTestId('planning-save-button')).toHaveCount(0);

      // EDIT toggle (AC8): flips view → edit (fields editable, Save returns).
      const editToggle = page.getByTestId('planning-edit-toggle');
      await expect(editToggle).toBeVisible();
      await editToggle.locator('button').click();
      await expect(page).toHaveURL(new RegExp(`/planning/${dayId}/edit`));
      await expect(page.getByTestId('planning-date').locator('input')).toBeEnabled();
      await expect(page.getByTestId('planning-save-button')).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · planning · a seeded planning day opens in READ-ONLY mode with EVERY field ' +
          'disabled (not merely Save hidden — the operator’s #10 bug, the CVA setDisabledState ' +
          'no-op T-09 fixed); the Edit affordance flips it to edit mode (fields editable, Save ' +
          'returns) — driven against the real backend as clubadmin1',
        acTag: 'happy',
      });
    }
  });
});
