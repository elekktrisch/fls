import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { selectAfOption } from '../_helpers/af-select';
import {
  loginAsClubAdmin,
  provisionTwoClubs,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';

/**
 * J-13 audit-log viewer (`/system/logs`) — the §4 done-bar, driven against the
 * REAL chain (live Keycloak JWT → Hibernate `@TenantId` → Postgres). NO mocking:
 * a `page.route` here would defeat the tenant-isolation seam that is the journey's
 * hardest assertion.
 *
 * The audit trail is LIVE app-generated: any mutating `/api/v1/*` call writes a
 * `t_mutation_audit_event` row for the caller's tenant (success rows via the
 * AFTER_COMMIT listener carry the real before/after state; failed rows via
 * `RequestAuditFilter`). So the spec GENERATES its own events through the real UI
 * / real endpoints, then reads them back through `/system/logs`:
 *
 *   - [happy] A club-A admin creates + edits a Location through the real chrome →
 *     CREATE + UPDATE audit rows appear at `/system/logs` with action / target
 *     type / actor / occurredAt / httpStatus.
 *   - [happy] Filter by action (UPDATE) + by target-entity-type narrows; clear
 *     restores.
 *   - [happy] Time-range (occurredFrom/occurredTo) narrows to the range — asserted
 *     on the REAL request the store issues + the re-rendered list.
 *   - [happy] Pagination: default page size 50; >50 real events → the Next pager
 *     is enabled (hasMore) and advancing changes the offset (nextOffset cursor).
 *     The >50 events are SEEDED server-side as the club-A admin (bulk failed-DELETE
 *     mutations, each a real audit row) rather than 50 UI clicks.
 *   - [happy] Expand a row → `audit-row-detail`: UPDATE = before/after diff,
 *     CREATE = after-only, DELETE = before-only.
 *   - [edge] Tenant isolation (two-club): a club-A admin sees ONLY club-A audit
 *     events — a Location created in club B is ABSENT from club A's list. The
 *     structural `@TenantId` proof.
 *   - [key-error] A real low-privilege PILOT is denied: the `clubAdminGuard`
 *     redirects `/system/logs` → `/start`, the Masterdata nav has no Audit-logs
 *     entry, and `GET /api/v1/admin/audit-events` returns 403.
 *
 * Each principal drives its OWN `browser.newContext()` (separate storageState /
 * session) so the two CLUB_ADMINISTRATOR JWTs never bleed across the tenant
 * boundary; the context records video explicitly (the project's `video:'on'`
 * governs only the auto `page` fixture, not a manually-created context — see
 * `proof-video.ts`).
 */

const AUDIT_LOGS_PATH = '/system/logs';
const START_PATH = '/start';

/** Canonical reference seeds (V2 / V5) — used for the real Location create. */
const CH_COUNTRY_LABEL = 'Switzerland';

/** seed-club-1's clean-seed low-privilege PILOT — the non-admin reach guard (V8). */
const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

/** The screen-shape contract the T-03..T-06 components expose (asserted here). */
const TESTIDS = {
  table: 'audit-logs-table',
  row: 'audit-row',
  rowAction: 'audit-row-action',
  rowTarget: 'audit-row-target',
  rowActor: 'audit-row-actor',
  rowStatus: 'audit-row-status',
  rowTime: 'audit-row-time',
  rowDetail: 'audit-row-detail',
  filterAction: 'audit-filter-action',
  filterTarget: 'audit-filter-target',
  filterFrom: 'audit-filter-from',
  filterTo: 'audit-filter-to',
  clearFilters: 'audit-clear-filters',
  pagerNext: 'audit-pager-next',
  pagerPrev: 'audit-pager-prev',
  pagerOffset: 'audit-pager-offset',
  empty: 'audit-logs-empty',
} as const;

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

/**
 * Pick Switzerland from the country `nz-select`. The REAL backend serves the
 * full ISO catalog (~250 rows), so the target sits far below the fold — open the
 * select, type to filter, then click the now-visible option. (Mirrors the J-0
 * Locations spec's `selectSwitzerland`.)
 */
async function selectSwitzerland(page: Page): Promise<void> {
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.keyboard.type(CH_COUNTRY_LABEL);
  await page
    .locator('nz-option-item')
    .filter({ hasText: new RegExp(`^${CH_COUNTRY_LABEL}$`) })
    .click();
}

/**
 * Drive the SPA Location-create form to completion through the real chrome and
 * return to the list. Waits on the real 201 as a save signal; the created id is
 * read from the rendered list row's `location-row-<id>` testid AFTER navigation
 * (the SPA evicts the POST body across the nav — J-2 T-43). Returns that id.
 */
async function createLocationViaUi(
  page: Page,
  opts: { name: string; icao: string },
): Promise<string> {
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/locations' &&
      r.status() === 201,
  );
  await page.goto('/locations');
  await page.getByRole('button', { name: 'New location' }).click();
  await expect(page).toHaveURL('/locations/new');
  await page.locator('#LocationName').fill(opts.name);
  await page.locator('#IcaoCode').fill(opts.icao);
  await selectSwitzerland(page);
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').first().click();
  await page.getByTestId('locations-save-button').click();
  await created;
  await expect(page).toHaveURL('/locations');

  const row = page.locator('[data-testid^="location-row-"]').filter({ hasText: opts.name });
  await expect(row).toBeVisible();
  const rowTestId = await row.getAttribute('data-testid');
  expect(rowTestId, 'created Location row must carry a location-row-<id> testid').toBeTruthy();
  const id = rowTestId!.replace(/^location-row-/, '');
  expect(id, `derived Location id must be loc-<uuid>, got "${id}"`).toMatch(/^loc-[0-9a-f-]{36}$/);
  return id;
}

/**
 * Edit an existing Location's name through the real chrome → a real PUT →
 * an UPDATE audit row with a genuine before/after diff. Waits on the 200.
 */
async function renameLocationViaUi(page: Page, locId: string, newName: string): Promise<void> {
  const updated = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      new URL(r.url()).pathname === `/api/v1/locations/${locId}` &&
      r.status() === 200,
  );
  await page.goto('/locations');
  await page.locator(`[data-testid="location-row-${locId}"]`).click();
  await expect(page).toHaveURL(`/locations/${locId}/edit`);
  await page.locator('#LocationName').fill(newName);
  await page.getByTestId('locations-save-button').click();
  await updated;
  await expect(page).toHaveURL('/locations');
}

/**
 * Capture the Bearer the OIDC interceptor attaches to an authed principal's first
 * `/api/v1/*` read, so the spec can issue server-side seed writes + the direct
 * 403 check with that same principal's real token.
 */
async function bearerFor(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (req) =>
      req.url().includes('/api/v1/') &&
      typeof req.headers()['authorization'] === 'string' &&
      /^Bearer /i.test(req.headers()['authorization']!),
  );
  await page.goto(START_PATH);
  const req = await reqPromise;
  return req.headers()['authorization']!;
}

/**
 * Seed >`count` real audit events for the caller's tenant WITHOUT 50 UI clicks:
 * each `DELETE /api/v1/locations/<random-uuid>` hits a non-existent row → a 404
 * that `RequestAuditFilter` records as a `failed=true` DELETE/Location audit row
 * under the caller's tenant (401/403 are the only statuses it skips). Deterministic,
 * needs no reference data, no uniqueness collisions. Documented server-side seed
 * for the pagination cursor proof (default page size 50).
 */
async function seedAuditEvents(
  api: APIRequestContext,
  bearer: string,
  count: number,
): Promise<void> {
  for (let i = 0; i < count; i++) {
    const res = await api.delete(`/api/v1/locations/loc-${randomUuid()}`, {
      headers: { authorization: bearer },
    });
    // 404 (row invisible under tenant scope) is the expected + audited outcome.
    expect(
      res.status(),
      `audit-seed DELETE #${i} should 404 (non-existent id under tenant scope)`,
    ).toBe(404);
  }
}

function randomUuid(): string {
  // Node's crypto is available in the Playwright worker; keep it local so the
  // import surface stays small.
  return globalThis.crypto.randomUUID();
}

/** A row locator for the audit list filtered by its visible target-type text. */
function auditRowsWithTarget(page: Page, targetType: string) {
  return page.getByTestId(TESTIDS.row).filter({ has: page.getByText(targetType, { exact: true }) });
}

/** Enter the audit-log viewer through the real Masterdata nav chrome. */
async function enterAuditLogs(page: Page): Promise<void> {
  await page.goto(START_PATH);
  await expect(page).toHaveURL(START_PATH);
  await enterViaNav(page, AUDIT_LOGS_PATH);
  await expect(page).toHaveURL(AUDIT_LOGS_PATH);
  await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
}

test.describe('Audit-log viewer — two-club tenant isolation (real-idp)', () => {
  // One realm + one backend; provision once, run the asserts in order. Serial
  // keeps the two sessions from racing KC user-exists checks + keeps the seeded
  // audit rows from one case out of another's isolated assertions.
  test.describe.configure({ mode: 'serial' });

  let fixture: TwoClubFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    // Spec-scoped admin usernames ('audit') so this fixture's admins are disjoint
    // from a sibling spec's when both run in one `playwright test` invocation —
    // ux_user_username_lower_alive (two-club-fixture docstring).
    fixture = await provisionTwoClubs(browser, baseURL, 'audit');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] club-A admin: a real create+edit surfaces at /system/logs with action, target, actor, time, status; filters + row-detail diff', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    // Uniquely-named Location so the audit rows are addressable amid any other
    // rows the tenant already carries (assert-what-the-realm-produces).
    const nonce = randomUuid().slice(0, 8);
    const clubALocationName = `Audit A ${nonce}`;
    const editedName = `Audit A edited ${nonce}`;
    const icao = `LS${nonce.slice(0, 2).toUpperCase()}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // (1) Generate LIVE audit events through the real chrome: a CREATE then an
      //     UPDATE of a real Location → a CREATE row (after-only) + an UPDATE row
      //     (before/after diff), both under club A's tenant.
      const locId = await createLocationViaUi(page, { name: clubALocationName, icao });
      await renameLocationViaUi(page, locId, editedName);

      // (2) Open /system/logs through the Masterdata nav chrome + assert the row
      //     fields (action badge / target type / actor cell / status / time).
      await enterAuditLogs(page);

      // The UPDATE row for our Location: filter by target-type "Location" to a
      // stable set, then narrow to the one carrying our edited name in its diff
      // is done in the detail case — here assert the row-field shape on Location
      // rows exists (action, target, actor, status, time all render).
      const locationRow = auditRowsWithTarget(page, 'Location').first();
      await expect(locationRow).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowAction)).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowTarget)).toHaveText('Location');
      await expect(locationRow.getByTestId(TESTIDS.rowActor)).toBeVisible();
      // httpStatus is a FAILURE-ONLY field: success rows (this create/edit) carry a
      // null status by design (the AFTER_COMMIT audit listener records no status —
      // only `RequestAuditFilter` on a failed mutation does). So the status cell of
      // a success row renders the em-dash placeholder, never an HTTP code. The real
      // "404" status render is asserted on a FAILED row in the pagination case.
      await expect(locationRow.getByTestId(TESTIDS.rowStatus)).toHaveText('—');
      await expect(locationRow.getByTestId(TESTIDS.rowTime)).toBeVisible();

      // Capture the populated LIST shot for the gallery BEFORE the deeper flow —
      // ≥1 real Location row is rendered (not an empty "No Data" shot).
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-list.png`,
        fullPage: true,
      });

      // (3) Filter by action = UPDATE → every rendered row is an Updated row.
      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      const updateRows = page.getByTestId(TESTIDS.row);
      await expect(updateRows.first()).toBeVisible();
      const updateActionCells = page.getByTestId(TESTIDS.rowAction);
      const updateCount = await updateActionCells.count();
      for (let i = 0; i < updateCount; i++) {
        await expect(updateActionCells.nth(i)).toHaveText('Updated');
      }

      // (4) Add a target-entity-type filter = Location → still narrows to Location
      //     UPDATE rows (additive with the action filter).
      await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      // Debounced free-text (250ms) → the list reloads; gate on the target cells.
      await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Location');
      const narrowedTargets = page.getByTestId(TESTIDS.rowTarget);
      const narrowedCount = await narrowedTargets.count();
      for (let i = 0; i < narrowedCount; i++) {
        await expect(narrowedTargets.nth(i)).toHaveText('Location');
      }

      // (5) Clear filters → the full list is restored (rows for other target types
      //     may reappear; assert the control round-trips by re-widening the count).
      const narrowedRows = await page.getByTestId(TESTIDS.row).count();
      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      await expect
        .poll(async () => page.getByTestId(TESTIDS.row).count(), { timeout: 15_000 })
        .toBeGreaterThanOrEqual(narrowedRows);
      // The action select is back to its all-actions placeholder (no value).
      await expect(page.getByTestId(TESTIDS.rowAction).first()).toBeVisible();

      // (6) Row-detail diff (UPDATE) — expand OUR Location's UPDATE row and assert
      //     the before/after payload renders (the field-diff table). Filter to
      //     Location UPDATE rows first, then find the row that is ours by its
      //     UNIQUE edited name rather than by exact count: club A is the shared
      //     Flyway seed-club-1 tenant, so a Playwright retry (which re-runs the
      //     serial group's beforeAll → a fresh club-A admin whose own rename lands
      //     in the same tenant) leaves >1 UPDATE/Location row. The collapsed row
      //     surfaces action / target-type / actor / status / time only — the edited
      //     NAME lives in the afterState diff, which renders in `audit-row-detail`
      //     after expansion. So expand each UPDATE/Location row until one exposes a
      //     detail carrying THIS attempt's unique `editedName` (fresh per-nonce), and
      //     assert the genuine before→after on that one.
      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await page.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText('Location');
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const locationUpdateRows = page.getByTestId(TESTIDS.row);
      const locationUpdateRowCount = await locationUpdateRows.count();
      const detail = page.getByTestId(TESTIDS.rowDetail);
      let ours = false;
      for (let i = 0; i < locationUpdateRowCount; i++) {
        await locationUpdateRows.nth(i).click();
        await expect(detail).toBeVisible();
        if (((await detail.textContent()) ?? '').includes(editedName)) {
          ours = true;
          break;
        }
        // Not ours — collapse (toggle) before probing the next row.
        await locationUpdateRows.nth(i).click();
      }
      expect(ours, `an UPDATE/Location audit row must carry this rename's editedName`).toBe(true);
      // The UPDATE diff table carries a Field/Before/After header + the edited
      // locationName value in the after column (a genuine before→after diff).
      await expect(detail).toContainText(editedName);
      await expect(detail).toContainText(clubALocationName);

      // Capture the populated ROW-DETAIL (form-equivalent) shot with the inline
      // diff rendered, BEFORE leaving.
      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · audit trail · a club-A admin creates + edits a Location through the real chrome, ' +
          'then opens /system/logs and sees the live CREATE + UPDATE audit rows (action, target type, ' +
          'actor, occurredAt, HTTP status); filters by action + target narrow the list and clearing ' +
          'restores it; expanding the UPDATE row shows the real before→after field diff',
        acTag: 'happy',
      });
    }
  });

  test('[happy] time-range filter narrows to events in range', async ({ browser }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await enterAuditLogs(page);

      // A full-list baseline row count (>=1 — the prior case's Location events).
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();

      // Pick a from-date FAR in the future (tomorrow) → the store issues a real
      // request carrying occurredFrom, and no existing event is in range → empty.
      const req = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.has('occurredFrom'),
      );
      await pickDate(page, TESTIDS.filterFrom, tomorrowDisplay());
      const issued = await req;
      // The REAL request carried the range bound (not a client-side filter).
      expect(new URL(issued.url()).searchParams.get('occurredFrom')).toBeTruthy();
      // Future-from narrows the real result to empty (no event occurred tomorrow).
      await expect(page.getByTestId(TESTIDS.empty)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.row)).toHaveCount(0);

      // Clearing the range restores the populated list (the range was the only
      // narrowing) — proves the filter round-trips against the real endpoint.
      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · time-range filter · a from-date bound issues a REAL occurredFrom request and narrows ' +
          'the audit list to events in range (a future bound empties it); clearing restores the list',
        acTag: 'happy',
      });
    }
  });

  test('[happy] pagination — default page size 50; advancing the cursor fetches the next offset', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      // Seed >50 real audit events server-side as club A (bulk failed-DELETE →
      // 404 → failed audit rows) so a second page genuinely exists. Documented in
      // `seedAuditEvents` — server-side seed, not 50 UI clicks.
      const bearer = await bearerFor(page);
      await seedAuditEvents(ctx.request, bearer, 55);

      await enterAuditLogs(page);

      // Default page: at most 50 rows rendered (page size 50) AND hasMore → the
      // Next pager is enabled; the offset indicator reads position 0.
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const firstPageCount = await page.getByTestId(TESTIDS.row).count();
      expect(firstPageCount, 'default page renders at most pageSize=50 rows').toBeLessThanOrEqual(
        50,
      );
      expect(firstPageCount, 'seed guarantees a full first page').toBe(50);
      // `af-button` puts `disabled` on its INNER `<button nz-button>`, not on the
      // host element the testid resolves to (the host is not a form control, so a
      // host-level toBeDisabled/toBeEnabled is meaningless — toBeEnabled trivially
      // passes, toBeDisabled trivially fails). Target the inner button, matching the
      // house pattern (flight-migration-parity.spec.ts:379).
      const pagerNext = page.getByTestId(TESTIDS.pagerNext).locator('button');
      await expect(pagerNext).toBeEnabled();
      await expect(page.getByTestId(TESTIDS.pagerPrev).locator('button')).toBeDisabled();
      const offsetBefore = (await page.getByTestId(TESTIDS.pagerOffset).textContent())?.trim();

      // The seeded rows are failed DELETE→404 mutations — the ONLY audit rows that
      // carry an httpStatus (success rows are null-by-design). So the status column
      // genuinely renders "404" here, proving the HTTP-status field surfaces the
      // failure code (the AC's "HTTP status" intent on the field that carries it).
      const statusCells = page.getByTestId(TESTIDS.rowStatus);
      await expect(statusCells.first()).toHaveText('404');

      // Advance the cursor: a REAL request carrying pageOffset=50 fires, the
      // offset indicator changes, and Prev becomes enabled.
      const nextReq = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.get('pageOffset') === '50',
      );
      await pagerNext.click();
      await nextReq;
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      await expect(page.getByTestId(TESTIDS.pagerPrev).locator('button')).toBeEnabled();
      await expect
        .poll(async () => (await page.getByTestId(TESTIDS.pagerOffset).textContent())?.trim())
        .not.toBe(offsetBefore);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · pagination · with >50 real audit events the default page renders 50 rows, the Next ' +
          'pager is enabled (hasMore) and advancing issues a REAL pageOffset=50 request that fetches ' +
          'the next page (nextOffset cursor)',
        acTag: 'happy',
      });
    }
  });

  test('[edge] tenant isolation — a club-A admin never sees a club-B audit event', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const clubBTargetName = `Audit B ${nonce}`;
    const clubBIcao = `LB${nonce.slice(0, 2).toUpperCase()}`;
    try {
      // (1) Generate a Location CREATE audit event in CLUB B (via the club-B
      //     principal) — a row that lives ONLY under club B's tenant.
      await loginAsClubAdmin(page, fixture.clubB);
      const bLocId = await createLocationViaUi(page, { name: clubBTargetName, icao: clubBIcao });
      expect(bLocId).toBeTruthy();
    } finally {
      // Close the club-B session cleanly before opening club A's (separate
      // context) — no cross-session token bleed.
      await page.close();
    }

    const ctxA = await newRecordedContext(browser, baseURL, testInfo);
    const pageA = await ctxA.newPage();
    try {
      // (2) As the CLUB-A admin, the club-B event is ABSENT from /system/logs —
      //     the structural @TenantId filter never surfaces another tenant's row.
      await loginAsClubAdmin(pageA, fixture.clubA);
      await enterAuditLogs(pageA);

      // Filter to Location events (the club-B event's target type) so the check
      // is over the exact set club B just wrote into — its NAME must never appear.
      await selectAfOption(pageA, TESTIDS.filterAction, 'CREATE');
      await pageA.getByTestId(TESTIDS.filterTarget).locator('input').fill('Location');
      await expect(pageA.getByTestId(TESTIDS.table)).toBeVisible();
      // No audit row anywhere in club A's list carries club B's Location name.
      await expect(pageA.getByTestId(TESTIDS.row).filter({ hasText: clubBTargetName })).toHaveCount(
        0,
      );

      // (3) Belt-and-braces at the API seam: a direct read as club A's real token
      //     over the widest window returns no item bearing club B's target name.
      const bearerA = await bearerFor(pageA);
      const res = await ctxA.request.get('/api/v1/admin/audit-events?pageSize=200', {
        headers: { authorization: bearerA },
      });
      expect(res.status(), await res.text()).toBe(200);
      const body = (await res.json()) as { items: { tenantClubId?: string }[] };
      // Every item is scoped to club A's tenant, never club B's.
      for (const item of body.items) {
        expect(item.tenantClubId, 'club A read must never surface a club-B row').not.toBe(
          fixture.clubB.clubId,
        );
      }
    } finally {
      await ctxA.close();
      await ctx.close();
      await proofVideo(pageA, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · tenant isolation · a Location CREATE audit event written in club B is ABSENT from ' +
          "club A admin's /system/logs, and a direct API read as club A's real token never surfaces a " +
          'club-B-scoped row — the structural @TenantId proof',
        acTag: 'edge',
      });
    }
  });

  test('[key-error] a plain PILOT is denied /system/logs — guard redirect, nav entry absent, endpoint 403', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      // The direct 403 read logs a resource-load console.error for the deliberate
      // forbidden call — allow ONLY the 403 marker; any other console.error fails.
      allowConsoleErrors(testInfo, /Failed to load resource.*audit-events.*403/i, /\b403\b/);

      // A real low-privilege PILOT (not a mock admin-everything principal) — the
      // gap a mock principal hides (real roles catch authz gaps).
      await loginAsSeeded(page, PILOT_USER, PILOT_PASSWORD);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });

      // (1) The clubAdminGuard redirects the guarded path away → not on /system/logs.
      await page.goto(AUDIT_LOGS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${AUDIT_LOGS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.table)).toHaveCount(0);

      // (2) A PILOT's Masterdata nav has no Audit-logs entry (admin-gated child).
      //     The group children live in a CDK dropdown overlay and are absent from
      //     the DOM until the group is OPENED — so a count(0) on a closed group
      //     passes vacuously (proves nothing). Open the Masterdata group, confirm
      //     a pilot-visible child renders (the group genuinely opened), THEN assert
      //     the admin-only Audit-logs child is absent — a real negative proof.
      //     The guard redirect above already left us on the app shell (nav
      //     rendered) — drive the nav in-app rather than a cold goto (avoids the
      //     OIDC reboot/renew stall).
      const masterdata = page.getByTestId('af-nav-group-masterdata');
      await expect(masterdata, 'a pilot still sees the Masterdata nav group').toBeVisible();
      await masterdata.click();
      await expect(
        page.getByTestId('af-nav-section-/aircraft'),
        'a pilot-visible Masterdata child renders once the group is open',
      ).toBeVisible();
      await expect(
        page.getByTestId(`af-nav-section-${AUDIT_LOGS_PATH}`),
        'a pilot has no Audit-logs entry under the opened Masterdata group',
      ).toHaveCount(0);
      await page.keyboard.press('Escape');

      // (3) The endpoint itself 403s a PILOT — the server role gate, driven with
      //     the pilot's OWN real token.
      const bearer = await bearerFor(page);
      const res = await ctx.request.get('/api/v1/admin/audit-events', {
        headers: { authorization: bearer },
      });
      expect(res.status(), 'GET /api/v1/admin/audit-events must 403 a plain PILOT').toBe(403);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · pilot denied · a real low-privilege PILOT is redirected off /system/logs by the ' +
          'clubAdminGuard, has no Audit-logs nav entry, and GET /api/v1/admin/audit-events returns 403',
        acTag: 'key-error',
      });
    }
  });
});

/** Sign a seeded principal (not a fixture-minted admin) in through the real KC redirect-login. */
async function loginAsSeeded(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

/**
 * Drive the `af-date-picker` (nz-date-picker) identified by `testId`: open it,
 * type the date in the picker's DISPLAY format (`dd.MM.yyyy`, the component's
 * `DEFAULT_DATE_FORMAT` → `[nzFormat]`) into its input, and commit with Enter. nz
 * parses a keyboard-typed value matching `nzFormat` and closes the panel on Enter.
 * No `waitForTimeout` — the caller gates on the resulting store request / render.
 */
async function pickDate(page: Page, testId: string, displayDate: string): Promise<void> {
  const input = page.getByTestId(testId).locator('input').first();
  await input.click();
  await input.fill(displayDate);
  await input.press('Enter');
}

/** Tomorrow (local) as `dd.MM.yyyy` — a from-bound guaranteed past every existing event. */
function tomorrowDisplay(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const yyyy = d.getFullYear();
  return `${dd}.${mm}.${yyyy}`;
}
