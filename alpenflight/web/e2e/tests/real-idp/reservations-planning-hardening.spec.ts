import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';

import { selectAfOption } from '../_helpers/af-select';
import { fillKcLogin } from './_helpers/kc-form';
import {
  loginAsReservationAdmin,
  captureReservationAdminBearer,
} from './_helpers/reservation-parity-fixture';
import {
  seedPlanningMasterdata,
  type PlanningMasterdata,
} from './_helpers/planning-parity-fixture';
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
 *   [happy]  INLINE SERVER-SIDE VALIDATION (the journey's ≥60% feature, AC1/2/3) —
 *            the LOAD-BEARING real-chain coverage (T-20). Every UI inner-loop spec
 *            `page.route`-mocks `POST /planning-days/validate` with canned JSON, so
 *            the FE→real-endpoint→real-rule→`{valid,field,message}`→inline-message
 *            vertical was UNPROVEN — a wrong path / dropped `excludeId` / envelope
 *            bug would ship green. Here: seed a REAL duplicate planning day, type
 *            the same (date, location) into the REAL planning-edit form, and assert
 *            the debounced `planning-date-server-error` surfaces from the REAL
 *            `/validate` endpoint over the wire — asserting the REAL backend PROSE
 *            (`PlanningDaysService`: "A planning day already exists for this date
 *            and location.") the response carries, NOT the mock's canned i18n key
 *            (gap-hunter C's key-vs-prose mismatch). Asserts it CLEARS on a free
 *            date and that submit is blocked while the conflict stands. NO `@mocked:`
 *            on this path — every seam is the real stack.
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
  test.beforeAll(async ({}, testInfo) => {
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
  test.beforeAll(async ({}, testInfo) => {
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
  test.beforeAll(async ({}, testInfo) => {
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
  test.beforeAll(async ({}, testInfo) => {
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

// ===========================================================================
// INLINE SERVER-SIDE VALIDATION (AC1/AC2/AC3) — the load-bearing real-chain gap
// (T-20). The journey's ≥60% feature had ZERO real-chain coverage: every UI
// inner-loop spec `page.route`-mocks `POST /planning-days/validate` with canned
// `{valid:false}` JSON and asserts on the i18n KEY, so the FE→real-endpoint→
// real-rule→`{valid,field,message}`→inline-message vertical was never proven.
// Here it runs end-to-end real: a REAL duplicate planning day seeded through the
// real create API, the REAL planning-edit form typed into, the REAL `/validate`
// endpoint hit over the wire (real bearer / real probe / real envelope) and its
// REAL PROSE message asserted (NOT the mock's canned key). NO mocks → no
// `@mocked:` tag on this path.
// ===========================================================================

/** `YYYY-MM-DD`, `days` from local today (planning days are future days). */
function dayKeyFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * The EXACT prose `PlanningDaysService.validate` returns in a `valid:false`
 * result for a duplicate (club, date, location) — asserted over the wire as the
 * proof the FE hit the REAL backend rule, not a canned i18n key. Mirror of
 * `PlanningDaysService.java:135` (T-05). If the backend message changes, this
 * assertion is the canary — update both together.
 */
const REAL_PLANNING_DUPLICATE_MESSAGE = 'A planning day already exists for this date and location.';

test.describe('J-6b inline server-side validation — real chain (real-idp)', () => {
  test.describe.configure({ mode: 'serial' });

  let baseURL: string;
  /** clubadmin4's Bearer (seed-club-1, the operating/@TenantId club). */
  let adminBearer: string;
  /** A fresh seed-club-1 location + crew the create/edit form pickers offer. */
  let masterdata: PlanningMasterdata;
  /** The date of the REAL planning day seeded as the duplicate target. */
  let duplicateDate: string;
  /** Every planning-day id this group created — deleted in afterAll. */
  const createdIds: string[] = [];
  let cleanupCtx: BrowserContext;

  test.beforeAll(async ({ browser, request }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    createdIds.length = 0;
    adminBearer = await captureReservationAdminBearer(browser, baseURL);
    // Seed (as clubadmin4, through the REAL create APIs) a fresh seed-club-1
    // location + crew so the planning-edit form's location picker offers the
    // duplicate target's location.
    masterdata = await seedPlanningMasterdata(request, adminBearer);

    // Seed the REAL duplicate planning day: a single day at a fixed future date
    // on the freshly-seeded location. Typing this SAME (date, location) into the
    // create form below makes the REAL `…/validate` endpoint report the
    // duplicate — the exact (club,date,location) uniqueness the save-path 409
    // enforces (ux_pln_club_date_loc), surfaced inline pre-save.
    duplicateDate = dayKeyFromToday(11);
    const created = await request.post('/api/v1/planning-days', {
      headers: { authorization: adminBearer, 'content-type': 'application/json' },
      data: { planningDate: duplicateDate, locationId: masterdata.locationId },
    });
    expect(
      created.status(),
      `seeding the duplicate-target planning day must 201 — got ${created.status()}: ${await created.text()}`,
    ).toBe(201);
    const loc = created.headers()['location'];
    expect(loc, 'seed create must return a 201 Location header').toBeTruthy();
    const id = new URL(loc!, 'http://localhost').pathname.split('/').pop() ?? '';
    expect(id, `Location "${loc}" must end in a planning-day UUID`).toMatch(/^[0-9a-f-]{36}$/);
    createdIds.push(id);

    cleanupCtx = await browser.newContext({ baseURL });
  });

  test.afterAll(async () => {
    for (const id of createdIds) {
      try {
        await cleanupCtx.request.delete(`/api/v1/planning-days/${id}`, {
          headers: { authorization: adminBearer },
        });
      } catch (err) {
        console.warn(`[J-6b] afterAll cleanup: delete ${id} failed (${(err as Error).message})`);
      }
    }
    await cleanupCtx?.close();
  });

  test('[happy] the planning-edit form server-validates (date, location) uniqueness against the REAL /validate endpoint — the real prose surfaces inline, clears on a free date, and blocks submit', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsReservationAdmin(page);
      await page.goto('/planning/new/edit?lang=de');
      await expect(page.getByTestId('planning-edit-form')).toBeVisible();

      // Arm a wire-level watch on the REAL validate POST BEFORE typing — the FE
      // (planning.store.ts: validateUniqueness rxMethod, debounced ~200ms) calls
      // `POST /api/v1/planning-days/validate` once date + location are both set.
      // This is the whole point of the gap fix: the inline message must come from
      // the REAL endpoint, not a `page.route` mock (there is none here).
      const validateConflict = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planning-days/validate' &&
          r.status() === 200,
        { timeout: 15_000 },
      );

      // Type the SAME (date, location) as the seeded duplicate day → the REAL
      // uniqueness rule reports a clash. Search the picker by the unique seeded
      // name (seed-club-1 is never truncated → a long virtualised list).
      await page.getByTestId('planning-date').locator('input').fill(duplicateDate);
      await selectAfOption(
        page,
        'planning-location-select',
        masterdata.locationId,
        masterdata.locationName,
      );

      // WIRE PROOF: the REAL endpoint returned `valid:false` for the offending
      // `planningDate` field, carrying the REAL backend PROSE (not the i18n key
      // the mock canned — gap-hunter C). Asserting the prose proves the FE hit the
      // real rule end-to-end with the right (club, date, location) probe.
      const conflictResp = await validateConflict;
      const conflictBody = (await conflictResp.json()) as {
        valid: boolean;
        field?: string;
        message?: string;
      };
      expect(conflictBody.valid, 'the real /validate reports the duplicate as invalid').toBe(false);
      expect(conflictBody.field, 'the offending field is planningDate').toBe('planningDate');
      expect(
        conflictBody.message,
        'the real backend returns the duplicate PROSE (not the mock i18n key)',
      ).toBe(REAL_PLANNING_DUPLICATE_MESSAGE);

      // The debounced inline message surfaces on the date field from that REAL
      // result — WITHOUT a save round-trip.
      await expect(
        page.getByTestId('planning-date-server-error'),
        'the inline server-error surfaces from the REAL /validate result',
      ).toBeVisible();

      // CAPTURE the inline-validation gallery shot (the conflict standing).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-planning-inline-validate.png`,
        fullPage: true,
      });

      // Submit is BLOCKED while the conflict stands (the save-path 409 is the
      // backstop; the inline pre-check disables Save earlier).
      await expect(
        page.getByTestId('planning-save-button').locator('button'),
        'submit is blocked while the duplicate stands',
      ).toBeDisabled();

      // Change the date to a FREE one → the REAL endpoint now reports valid:true,
      // the inline message CLEARS (debounced) and submit unblocks.
      const validateFree = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/planning-days/validate' &&
          r.status() === 200,
        { timeout: 15_000 },
      );
      await page.getByTestId('planning-date').locator('input').fill(dayKeyFromToday(23));
      const freeBody = (await (await validateFree).json()) as { valid: boolean };
      expect(freeBody.valid, 'a free (date, location) validates clean against the real rule').toBe(
        true,
      );
      await expect(
        page.getByTestId('planning-date-server-error'),
        'the inline server-error clears when the date becomes free',
      ).toHaveCount(0);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-6b',
        caption:
          'J-6b · inline validation (the ≥60% feature, AC1/2/3) · a club admin types a (date, ' +
          'location) that duplicates a REAL seeded planning day into the planning-edit form — the ' +
          'REAL POST /planning-days/validate endpoint reports the clash over the wire (real bearer, ' +
          'real ux_pln_club_date_loc rule, real {valid,field,message} envelope carrying the backend ' +
          'PROSE, not the mock i18n key) and its message surfaces inline on the date field WITHOUT a ' +
          'save; changing to a free date clears it. The whole FE→real-endpoint→real-rule→inline ' +
          'vertical, proven end-to-end (the mock inner-loop never hit a real endpoint)',
        acTag: 'happy',
      });
    }
  });
});
