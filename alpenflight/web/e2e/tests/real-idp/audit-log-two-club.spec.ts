import {
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test';
import { test, expect, watchConsoleErrors, allowConsoleErrors } from '../_helpers/console-guard';

import { enterViaNav } from '../_helpers/nav';
import { labelInTheLocaleTheSessionRenders } from '../_helpers/rendered-locale';
import { selectAfOption } from '../_helpers/af-select';
import { typeDateIntoAfDatePicker } from '../_helpers/af-date-picker';
import {
  ACTIVE_CLUB_STATE_ID,
  CH_COUNTRY_ID,
  loginAsClubAdmin,
  provisionTwoClubs,
  type ClubAdmin,
  type TwoClubFixture,
} from './_helpers/two-club-fixture';
import { fillKcLogin } from './_helpers/kc-form';
import { proofVideo } from './_helpers/proof-video';
import { registrantWireBody } from './_helpers/public-registration-fixture';
import { freshTestUser } from './_helpers/test-user';
import { AuditEventRowActorKind } from '../../../src/app/api/generated/model/auditEventRowActorKind';

const AUDIT_LOGS_PATH = '/system/logs';
const START_PATH = '/start';

const AUDIT_PAGE_SIZE = 50;
const SUCCESS_ROW_HTTP_STATUS_PLACEHOLDER = '—';

const CH_COUNTRY_LABEL = 'Switzerland';

const PILOT_USER = 'pilot1@example.com';
const PILOT_PASSWORD = 'pilot1-dev-2026!';

const REDACTED_SENTINEL = '[redacted]';

const THE_RAW_IDENTIFIER_SHAPE_AN_ACTOR_CELL_MUST_NEVER_RENDER =
  /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

const MASTERDATA_CHILD_RENDERED_ONLY_WHILE_THE_OVERLAY_IS_OPEN = 'af-nav-section-/aircraft';

const PERSON_TARGET = 'Person';
const LOCATION_TARGET = 'Location';
const CLUB_TARGET = 'Club';
const PUBLIC_REGISTRATION_TARGET = 'PublicFlightRegistration';

const A_KEYCLOAK_LOGIN_PLUS_REAL_UI_WRITES_PLUS_THE_AUDIT_SCREEN_TIMEOUT_MS = 120_000;
const APP_SHELL_PAINTS_AFTER_A_COLD_NAVIGATION_TIMEOUT_MS = 30_000;

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

interface AuditEventApiRow {
  id?: string;
  actorKind?: string;
  actorUserId?: string;
  systemActor?: boolean;
  tenantClubId?: string;
  targetEntityType?: string;
  beforeState?: Record<string, unknown>;
  afterState?: Record<string, unknown>;
}

interface ClubApiRow {
  id: string;
  name: string;
  slug: string | null;
}

async function newRecordedContext(
  browser: Browser,
  baseURL: string,
  testInfo: TestInfo,
): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL, recordVideo: { dir: testInfo.outputDir } });
  context.on('page', (p) => watchConsoleErrors(p, testInfo));
  return context;
}

async function selectSwitzerland(page: Page): Promise<void> {
  await page.getByTestId('locations-country-select').locator('nz-select').click();
  await page.keyboard.type(CH_COUNTRY_LABEL);
  await page
    .locator('nz-option-item')
    .filter({ hasText: new RegExp(`^${CH_COUNTRY_LABEL}$`) })
    .click();
}

async function createLocationViaUi(page: Page, opts: { name: string }): Promise<string> {
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
  await selectSwitzerland(page);
  await page.getByTestId('locations-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').first().click();
  const saveButton = page.getByTestId('locations-save-button');
  await expect(saveButton.locator('button')).toBeEnabled();
  await saveButton.click();
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

async function createAircraftViaUi(page: Page, immatriculation: string): Promise<void> {
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/aircraft' &&
      r.status() === 201,
  );
  await page.goto('/aircraft');
  await page.getByTestId('aircraft-new-button').locator('button').click();
  await expect(page).toHaveURL('/aircraft/new');
  await page.locator('#Immatriculation').fill(immatriculation);
  await page.getByTestId('aircraft-type-select').locator('nz-select').click();
  await page.locator('nz-option-item').filter({ hasText: 'Glider' }).first().click();
  const saveButton = page.getByTestId('aircraft-save-button');
  await expect(saveButton.locator('button')).toBeEnabled();
  await saveButton.locator('button').click();
  await created;
  await expect(page).toHaveURL('/aircraft');
}

async function createPersonViaUi(
  page: Page,
  opts: { firstname: string; lastname: string },
): Promise<void> {
  const created = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === '/api/v1/persons' &&
      r.status() === 201,
  );
  await page.goto('/persons');
  await expect(page.getByTestId('persons-table')).toBeVisible();
  await page.getByTestId('persons-new-button').click();
  await expect(page).toHaveURL('/persons/new');
  await page.getByTestId('firstname-input').locator('input').fill(opts.firstname);
  await page.getByTestId('lastname-input').locator('input').fill(opts.lastname);
  await page.getByTestId('person-save-button').click();
  await created;
  await expect(page).toHaveURL('/persons');
}

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

async function seedAuditEvents(
  api: APIRequestContext,
  bearer: string,
  count: number,
): Promise<void> {
  for (let i = 0; i < count; i++) {
    const res = await api.delete(`/api/v1/locations/loc-${randomUuid()}`, {
      headers: { authorization: bearer },
    });
    expect(
      res.status(),
      `audit-seed DELETE #${i} should 404 (non-existent id under tenant scope)`,
    ).toBe(404);
  }
}

function randomUuid(): string {
  return globalThis.crypto.randomUUID();
}

function auditRowsWithTarget(page: Page, targetType: string) {
  return page.getByTestId(TESTIDS.row).filter({ has: page.getByText(targetType, { exact: true }) });
}

async function filterAuditTarget(page: Page, targetEntityType: string): Promise<void> {
  const narrowedList = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
      new URL(r.url()).searchParams.get('targetEntityType') === targetEntityType &&
      r.status() === 200,
  );
  await page.getByTestId(TESTIDS.filterTarget).locator('input').fill(targetEntityType);
  await narrowedList;
  await expect(page.getByTestId(TESTIDS.rowTarget).first()).toHaveText(targetEntityType);
}

async function filterToCreatedLocations(page: Page): Promise<void> {
  await selectAfOption(page, TESTIDS.filterAction, 'CREATE');
  await filterAuditTarget(page, LOCATION_TARGET);
  await expect(page.getByTestId(TESTIDS.rowAction).first()).toHaveText('Created');
}

async function enterAuditLogs(page: Page): Promise<void> {
  await page.goto(START_PATH);
  await expect(page).toHaveURL(/\/start/);
  await expect(
    page.getByTestId('af-nav-group-masterdata').or(page.getByTestId('af-nav-burger')).first(),
    'the application shell painted after the cold navigation',
  ).toBeVisible({ timeout: APP_SHELL_PAINTS_AFTER_A_COLD_NAVIGATION_TIMEOUT_MS });
  await enterViaNav(page, AUDIT_LOGS_PATH);
  await expect(page).toHaveURL(AUDIT_LOGS_PATH);
  await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
  await dismissTheMasterdataOverlayThatCoversTheRows(page);
}

async function dismissTheMasterdataOverlayThatCoversTheRows(page: Page): Promise<void> {
  await page.keyboard.press('Escape');
  await expect(
    page.getByTestId(MASTERDATA_CHILD_RENDERED_ONLY_WHILE_THE_OVERLAY_IS_OPEN),
    'the nav overlay closed, so a row click reaches the row',
  ).toHaveCount(0);
}

async function expandNewestRow(page: Page) {
  const newest = page.getByTestId(TESTIDS.row).first();
  await expect(newest).toBeVisible();
  await newest.click();
  const detail = page.getByTestId(TESTIDS.rowDetail);
  await expect(detail).toBeVisible();
  return { newest, detail };
}

async function readAuditEvents(
  request: APIRequestContext,
  bearer: string,
  query: string,
): Promise<AuditEventApiRow[]> {
  const res = await request.get(`/api/v1/admin/audit-events?${query}`, {
    headers: { authorization: bearer },
  });
  expect(res.status(), await res.text()).toBe(200);
  const body = (await res.json()) as { items: AuditEventApiRow[] };
  return body.items;
}

async function openPublicRegistration(
  request: APIRequestContext,
  bearer: string,
  admin: ClubAdmin,
): Promise<string> {
  const externalClubId = `clb-${admin.clubId}`;
  const read = await request.get(`/api/v1/clubs/${externalClubId}`, {
    headers: { authorization: bearer },
  });
  expect(read.status(), await read.text()).toBe(200);
  const club = (await read.json()) as ClubApiRow;
  const slug = club.slug;
  expect(
    slug,
    'the provisioned club carries the slug its public form is published under',
  ).toBeTruthy();

  const updated = await request.put(`/api/v1/clubs/${externalClubId}`, {
    headers: { authorization: bearer, 'content-type': 'application/json' },
    data: {
      name: club.name,
      slug,
      publicRegistrationEnabled: true,
      countryId: CH_COUNTRY_ID,
      clubStateId: ACTIVE_CLUB_STATE_ID,
    },
  });
  expect(updated.status(), await updated.text()).toBe(200);
  return slug!;
}

async function submitScenicRegistrationWithoutAToken(
  request: APIRequestContext,
  clubSlug: string,
): Promise<void> {
  const res = await request.post(`/api/v1/public/clubs/${clubSlug}/scenic-flight-registrations`, {
    headers: { 'content-type': 'application/json' },
    data: { registrant: registrantWireBody({ privateEmail: freshTestUser().email }) },
  });
  expect(res.status(), await res.text()).toBe(201);
}

test.describe('Audit-log viewer — two-club tenant isolation (real-idp)', () => {
  test.describe.configure({
    mode: 'serial',
    timeout: A_KEYCLOAK_LOGIN_PLUS_REAL_UI_WRITES_PLUS_THE_AUDIT_SCREEN_TIMEOUT_MS,
  });

  let fixture: TwoClubFixture;
  let baseURL: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    baseURL = testInfo.project.use.baseURL ?? 'http://localhost:4201';
    fixture = await provisionTwoClubs(browser, baseURL, 'audit');
  });

  test.afterAll(async () => {
    await fixture?.dispose();
  });

  test('[happy] club-A admin: the audit row for their own write names them by username, and no row on the page leaves the actor empty', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const attributedLocationName = `Audit attribution ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await createLocationViaUi(page, { name: attributedLocationName });

      await enterAuditLogs(page);
      await filterToCreatedLocations(page);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-actor-attribution.png`,
        fullPage: true,
      });

      const newestCreateRow = page.getByTestId(TESTIDS.row).first();
      await expect(newestCreateRow).toBeVisible();
      await expect(
        newestCreateRow.getByTestId(TESTIDS.rowActor),
        'the row for this write names the administrator who made it, by username',
      ).toHaveText(fixture.clubA.user.email);

      const actorCells = page.getByTestId(TESTIDS.rowActor);
      const actorCellCount = await actorCells.count();
      expect(
        actorCellCount,
        'the CREATE + Location filter must leave at least one row to attribute',
      ).toBeGreaterThan(0);
      for (let i = 0; i < actorCellCount; i++) {
        await expect(
          actorCells.nth(i),
          'every audit row names an actor or names the system',
        ).not.toHaveText('');
      }

      await newestCreateRow.click();
      await expect(page.getByTestId(TESTIDS.rowDetail)).toContainText(attributedLocationName);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-33',
        caption:
          'J-33 · actor attribution · A club-A administrator creates a Location in the real ' +
          'application. At /system/logs the row for that write names the administrator by ' +
          'username, and no row on the page leaves the actor cell empty. The expanded row ' +
          'carries the created name. The signed-in user is a club administrator. This video ' +
          'does not show a system administrator.',
        acTag: 'happy',
      });
    }
  });

  test('[edge] club-A admin: every value of a Person write reads [redacted] in the audit trail, while a Location write keeps its name', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const redactedLastname = `Redaktion${nonce}`;
    const redactedFirstname = `Nadia${nonce}`;
    const readableLocationName = `Audit control ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await createLocationViaUi(page, { name: readableLocationName });
      await createPersonViaUi(page, {
        firstname: redactedFirstname,
        lastname: redactedLastname,
      });

      await enterAuditLogs(page);
      await filterAuditTarget(page, PERSON_TARGET);
      const person = await expandNewestRow(page);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-person-redacted.png`,
        fullPage: true,
      });

      const personValues = person.detail.locator('tbody tr td:last-child');
      const personValueCount = await personValues.count();
      expect(
        personValueCount,
        'the Person snapshot must carry field lines to redact',
      ).toBeGreaterThan(1);
      for (let i = 0; i < personValueCount; i++) {
        await expect(
          personValues.nth(i),
          'a denied entity stores no value in the audit trail',
        ).toHaveText(REDACTED_SENTINEL);
      }
      await expect(
        person.detail,
        'the name the operator typed never reaches the audit trail',
      ).not.toContainText(redactedLastname);
      await expect(person.detail).not.toContainText(redactedFirstname);

      await expect(
        person.newest.getByTestId(TESTIDS.rowAction),
        'the redacted row still names the action',
      ).toHaveText('Created');
      await expect(
        person.newest.getByTestId(TESTIDS.rowActor),
        'the redacted row still names the actor',
      ).toHaveText(fixture.clubA.user.email);
      await expect(
        person.newest.getByTestId(TESTIDS.rowTime),
        'the redacted row still names the time',
      ).not.toHaveText('');

      await filterAuditTarget(page, LOCATION_TARGET);
      const location = await expandNewestRow(page);
      await expect(
        location.detail,
        'an allow-listed entity keeps its values — the redactor is field policy, not a blanket',
      ).toContainText(readableLocationName);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · redaction · A club-A administrator creates a Person and a Location in the real ' +
          'application. At /system/logs every value of the Person row reads [redacted], and the ' +
          'typed name is absent, while the Location row of the same trail still shows its name. ' +
          'The redacted row keeps its action, its actor and its time.',
        acTag: 'edge',
      });
    }
  });

  test('[happy] club-B admin: an anonymous public registration reads as the public form, never as an empty cell or a raw identifier, and differently from the administrator row', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bearer = await bearerFor(page);

      const slug = await openPublicRegistration(ctx.request, bearer, fixture.clubB);
      await submitScenicRegistrationWithoutAToken(ctx.request, slug);

      await enterAuditLogs(page);

      const anonymousPublicActorLabel = await labelInTheLocaleTheSessionRenders(
        page,
        (translations) => translations.auditLogs.actor.anonymousPublic,
      );

      await filterAuditTarget(page, CLUB_TARGET);
      const authenticatedActorCell = page.getByTestId(TESTIDS.rowActor).first();
      await expect(
        authenticatedActorCell,
        'the administrator write on the same screen names its actor by username',
      ).toHaveText(fixture.clubB.user.email);
      const administratorCellAsRendered =
        (await authenticatedActorCell.textContent())?.trim() ?? '';

      await filterAuditTarget(page, PUBLIC_REGISTRATION_TARGET);

      const anonymousRows = await readAuditEvents(
        ctx.request,
        bearer,
        `targetEntityType=${PUBLIC_REGISTRATION_TARGET}&pageSize=${AUDIT_PAGE_SIZE}`,
      );
      expect(anonymousRows.length, 'the real read returns the submission row').toBeGreaterThan(0);

      const anonymousActors = page.getByTestId(TESTIDS.rowActor);
      await expect(
        page.getByTestId(TESTIDS.row),
        'the narrowed screen holds the rows the real read returns',
      ).toHaveCount(anonymousRows.length);
      for (let i = 0; i < anonymousRows.length; i++) {
        await expect(
          anonymousActors.nth(i),
          'the anonymous submission reads as the public form',
        ).toHaveText(anonymousPublicActorLabel);
      }

      const anonymousCellAsRendered = (await anonymousActors.first().textContent())?.trim() ?? '';
      expect(
        anonymousCellAsRendered,
        'the anonymous row leaves no empty actor cell, although it carries no actor id',
      ).not.toBe('');
      expect(
        anonymousCellAsRendered,
        'the anonymous row names the form, and never falls back to a raw identifier',
      ).not.toMatch(THE_RAW_IDENTIFIER_SHAPE_AN_ACTOR_CELL_MUST_NEVER_RENDER);
      expect(
        anonymousCellAsRendered,
        'the two rendered cells of this screen tell the anonymous write and the administrator apart',
      ).not.toBe(administratorCellAsRendered);

      const newest = anonymousRows[0]!;
      expect(newest.actorKind, 'the anonymous write is classified apart from a scheduled job').toBe(
        AuditEventRowActorKind.ANONYMOUS_PUBLIC,
      );
      expect(
        newest.systemActor,
        'a scheduled job is the system actor; an anonymous registrant is not',
      ).toBe(false);
      expect(newest.actorUserId, 'the anonymous registrant is no club user').toBeFalsy();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-anonymous-actor.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-33',
        caption:
          'J-33 · anonymous attribution · A registrant submits a public scenic-flight form with ' +
          'no token. At /system/logs the club-B administrator reads that row as the public form. ' +
          'The cell is not empty and shows no raw identifier, and it differs from the ' +
          'administrator row, which the same screen names by username. The real API row carries ' +
          'actorKind=ANONYMOUS_PUBLIC, systemActor=false and no actor user id. The public form ' +
          'submission succeeds here. This video does not show a rejected anonymous write, and it ' +
          'does not show a client IP.',
        acTag: 'happy',
      });
    }
  });

  test('[edge] tenant isolation — a club-A admin sees their own Location write and never the club-B one', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const clubBTargetName = `Audit B ${nonce}`;
    const clubAOwnTargetName = `Audit A own ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubB);
      const bLocId = await createLocationViaUi(page, { name: clubBTargetName });
      expect(bLocId).toBeTruthy();
    } finally {
      await page.close();
    }

    const ctxA = await newRecordedContext(browser, baseURL, testInfo);
    const pageA = await ctxA.newPage();
    try {
      await loginAsClubAdmin(pageA, fixture.clubA);
      const bearerA = await bearerFor(pageA);
      await createLocationViaUi(pageA, { name: clubAOwnTargetName });

      await enterAuditLogs(pageA);
      await filterToCreatedLocations(pageA);

      const newestForClubA = await expandNewestRow(pageA);
      await expect(
        newestForClubA.detail,
        'the newest Location CREATE the club-A admin reads is their own write',
      ).toContainText(clubAOwnTargetName);

      await pageA.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-tenant-isolation.png`,
        fullPage: true,
      });

      const items = await readAuditEvents(ctxA.request, bearerA, 'pageSize=200');
      expect(items.length, 'the club-A read returns rows to scope').toBeGreaterThan(0);
      expect(
        items.some((item) => item.tenantClubId === fixture.clubA.clubId),
        'the club-A read returns club-A rows',
      ).toBe(true);
      for (const item of items) {
        expect(item.tenantClubId, 'club A read must never surface a club-B row').not.toBe(
          fixture.clubB.clubId,
        );
      }
      expect(
        JSON.stringify(items),
        'the club-B Location name, written moments before, is in no row club A can read',
      ).not.toContain(clubBTargetName);
    } finally {
      await ctxA.close();
      await ctx.close();
      await proofVideo(pageA, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · tenant isolation · Club B writes a Location CREATE audit row, then club A writes ' +
          'its own. At /system/logs the club-A administrator expands the newest CREATE/Location ' +
          'row, and it carries the club-A name. A direct API read with the real club-A token ' +
          'returns club-A rows and no club-B row.',
        acTag: 'edge',
      });
    }
  });

  test('[edge] club-A admin: expanding an audit row renders the field-level snapshot table', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const snapshotLocationName = `Audit snapshot ${nonce}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await createLocationViaUi(page, { name: snapshotLocationName });

      await enterAuditLogs(page);
      await filterToCreatedLocations(page);

      const newestCreateRow = page.getByTestId(TESTIDS.row).first();
      await expect(newestCreateRow).toBeVisible();
      await newestCreateRow.click();

      const detail = page.getByTestId(TESTIDS.rowDetail);
      await expect(detail).toBeVisible();
      await expect(detail).toContainText(snapshotLocationName);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-row-detail.png`,
        fullPage: true,
      });

      const snapshotFieldRows = detail.locator('tbody tr');
      await expect(snapshotFieldRows.first()).toBeVisible();
      expect(
        await snapshotFieldRows.count(),
        'the expanded row must render one line for each field of the written entity',
      ).toBeGreaterThan(1);
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-32',
        caption:
          'J-32 · audit row detail · A club-A administrator creates a Location in the real ' +
          'application. At /system/logs the expanded row shows the field-level snapshot table, ' +
          'with one line for each field of the written Location.',
        acTag: 'edge',
      });
    }
  });

  test('[happy] club-A admin: a real create+edit surfaces at /system/logs with action, target, actor, time, status; filters + row-detail diff', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    const nonce = randomUuid().slice(0, 8);
    const clubALocationName = `Audit A ${nonce}`;
    const editedName = `Audit A edited ${nonce}`;
    const aircraftImmat = `AU-${nonce.toUpperCase()}`;
    try {
      await loginAsClubAdmin(page, fixture.clubA);

      const locId = await createLocationViaUi(page, { name: clubALocationName });
      await renameLocationViaUi(page, locId, editedName);

      await createAircraftViaUi(page, aircraftImmat);

      await enterAuditLogs(page);

      const locationRow = auditRowsWithTarget(page, LOCATION_TARGET).first();
      await expect(locationRow).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowAction)).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowTarget)).toHaveText(LOCATION_TARGET);
      await expect(locationRow.getByTestId(TESTIDS.rowActor)).toBeVisible();
      await expect(locationRow.getByTestId(TESTIDS.rowStatus)).toHaveText(
        SUCCESS_ROW_HTTP_STATUS_PLACEHOLDER,
      );
      await expect(locationRow.getByTestId(TESTIDS.rowTime)).toBeVisible();

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-list.png`,
        fullPage: true,
      });

      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      const updateRows = page.getByTestId(TESTIDS.row);
      await expect(updateRows.first()).toBeVisible();
      const updateActionCells = page.getByTestId(TESTIDS.rowAction);
      const updateCount = await updateActionCells.count();
      for (let i = 0; i < updateCount; i++) {
        await expect(updateActionCells.nth(i)).toHaveText('Updated');
      }

      const aircraftTargetRows = () =>
        page
          .getByTestId(TESTIDS.rowTarget)
          .filter({ has: page.getByText('Aircraft', { exact: true }) });

      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();

      await filterAuditTarget(page, 'Aircraft');
      await expect(
        aircraftTargetRows().first(),
        'the seeded Aircraft audit row must exist under a targetEntityType=Aircraft filter',
      ).toBeVisible();

      await filterAuditTarget(page, LOCATION_TARGET);
      const locationTargets = page.getByTestId(TESTIDS.rowTarget);
      const locationCount = await locationTargets.count();
      for (let i = 0; i < locationCount; i++) {
        await expect(locationTargets.nth(i)).toHaveText(LOCATION_TARGET);
      }
      await expect(
        aircraftTargetRows(),
        'a targetEntityType=Location filter must EXCLUDE the seeded Aircraft row',
      ).toHaveCount(0);

      const narrowedRows = await page.getByTestId(TESTIDS.row).count();
      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.table)).toBeVisible();
      await expect
        .poll(async () => page.getByTestId(TESTIDS.row).count(), { timeout: 15_000 })
        .toBeGreaterThanOrEqual(narrowedRows);
      await expect(page.getByTestId(TESTIDS.rowAction).first()).toBeVisible();

      await selectAfOption(page, TESTIDS.filterAction, 'UPDATE');
      await filterAuditTarget(page, LOCATION_TARGET);
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const locationUpdateRows = page.getByTestId(TESTIDS.row);
      const locationUpdateRowCount = await locationUpdateRows.count();
      const detail = page.getByTestId(TESTIDS.rowDetail);
      let foundRowCarryingThisRenamesEditedName = false;
      for (let i = 0; i < locationUpdateRowCount; i++) {
        await locationUpdateRows.nth(i).click();
        await expect(detail).toBeVisible();
        if (((await detail.textContent()) ?? '').includes(editedName)) {
          foundRowCarryingThisRenamesEditedName = true;
          break;
        }
        await locationUpdateRows.nth(i).click();
      }
      expect(
        foundRowCarryingThisRenamesEditedName,
        `an UPDATE/Location audit row must carry this rename's editedName`,
      ).toBe(true);
      await expect(detail).toContainText(editedName);
      await expect(detail).toContainText(clubALocationName);

      await page.screenshot({
        path: `${testInfo.outputDir}/alpenflight-audit-logs-form.png`,
        fullPage: true,
      });
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-30',
        caption:
          'J-30 · audit filter genuinely excludes · a club-A admin creates a Location AND an Aircraft ' +
          'through the real chrome, then at /system/logs filters targetEntityType=Location: the Location ' +
          'row is present and the Aircraft row is ABSENT (adversarially seeded — the excluded row is ' +
          'created and asserted absent, not merely "the visible rows are Location"); expanding the UPDATE ' +
          'row shows the real before→after field diff',
        acTag: 'happy',
      });
    }
  });

  test('[happy] a future from-bound empties the audit list through a real occurredFrom request; clearing restores it', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      await loginAsClubAdmin(page, fixture.clubA);
      await enterAuditLogs(page);

      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();

      const req = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.has('occurredFrom'),
      );
      await typeDateIntoAfDatePicker(page, TESTIDS.filterFrom, tomorrowDisplay());
      const issued = await req;
      expect(new URL(issued.url()).searchParams.get('occurredFrom')).toBeTruthy();
      await expect(page.getByTestId(TESTIDS.empty)).toBeVisible();
      await expect(page.getByTestId(TESTIDS.row)).toHaveCount(0);

      await page.getByTestId(TESTIDS.clearFilters).click();
      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
    } finally {
      await ctx.close();
      await proofVideo(page, testInfo, {
        journey: 'J-13',
        caption:
          'J-13 · time-range filter · a club-A admin sets the from-date to tomorrow at /system/logs: ' +
          'the screen issues a REAL occurredFrom request, every row drops and the empty state shows; ' +
          'clearing the filter brings the rows back. The control has date granularity, so all rows of ' +
          'one run share one date — this proof does NOT show an in-range row that stays beside an ' +
          'out-of-range row that drops.',
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

      const bearer = await bearerFor(page);
      await seedAuditEvents(ctx.request, bearer, AUDIT_PAGE_SIZE + 5);

      await enterAuditLogs(page);

      await expect(page.getByTestId(TESTIDS.row).first()).toBeVisible();
      const firstPageCount = await page.getByTestId(TESTIDS.row).count();
      expect(firstPageCount, 'default page renders at most pageSize=50 rows').toBeLessThanOrEqual(
        AUDIT_PAGE_SIZE,
      );
      expect(firstPageCount, 'seed guarantees a full first page').toBe(AUDIT_PAGE_SIZE);
      const pagerNext = page.getByTestId(TESTIDS.pagerNext).locator('button');
      await expect(pagerNext).toBeEnabled();
      await expect(page.getByTestId(TESTIDS.pagerPrev).locator('button')).toBeDisabled();
      const offsetBefore = (await page.getByTestId(TESTIDS.pagerOffset).textContent())?.trim();

      const statusCells = page.getByTestId(TESTIDS.rowStatus);
      await expect(statusCells.first()).toHaveText('404');

      const nextReq = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-events' &&
          new URL(r.url()).searchParams.get('pageOffset') === String(AUDIT_PAGE_SIZE),
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

  test('[key-error] a plain PILOT is denied /system/logs — guard redirect, nav entry absent, endpoint 403', async ({
    browser,
  }, testInfo) => {
    const ctx = await newRecordedContext(browser, baseURL, testInfo);
    const page = await ctx.newPage();
    try {
      allowConsoleErrors(testInfo, /Failed to load resource.*audit-events.*403/i, /\b403\b/);

      await loginAsSeeded(page, PILOT_USER, PILOT_PASSWORD);
      await page.waitForURL(/\/start$/, { timeout: 30_000 });

      await page.goto(AUDIT_LOGS_PATH);
      await expect(page).not.toHaveURL(new RegExp(`${AUDIT_LOGS_PATH}$`));
      await expect(page.getByTestId(TESTIDS.table)).toHaveCount(0);

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

async function loginAsSeeded(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByTestId('landing-topbar-sign-in').click();
  await page.waitForURL(/\/realms\/alpenflight\//);
  await fillKcLogin(page, username, password);
  await page.waitForURL((url) => !url.pathname.startsWith('/realms/'), { timeout: 30_000 });
}

function tomorrowDisplay(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const yyyy = d.getFullYear();
  return `${dd}.${mm}.${yyyy}`;
}
