import { type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';
import type { AuditEventPage } from '../../../src/app/api/generated/model/auditEventPage';
import type { AuditEventRow } from '../../../src/app/api/generated/model/auditEventRow';
import { AuditEventRowAction } from '../../../src/app/api/generated/model/auditEventRowAction';
import { AuditEventRowActorKind } from '../../../src/app/api/generated/model/auditEventRowActorKind';
import type { UserListItem } from '../../../src/app/api/generated/model/userListItem';
import { labelInTheLocaleTheSessionRenders } from '../_helpers/rendered-locale';

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const ACTOR_USER_ID = '019e30c3-2c00-7100-8000-000000000001';
const ACTOR_USERNAME = 'a.meier';
const ACTOR_KEYCLOAK_SUB = '2b3f1d84-9c11-4c1e-9a5d-6f0f1a2b3c4d';
const UNLISTED_ACTOR_KEYCLOAK_SUB = '7c4e2a19-3b55-4d02-8f61-5e0a9c8d7b6a';
const USER_ROW_ID = 'aud-019e30c3-2c00-7200-8000-000000000001';
const ANONYMOUS_ROW_ID = 'aud-019e30c3-2c00-7200-8000-000000000005';
const SCHEDULED_JOB_ROW_ID = 'aud-019e30c3-2c00-7200-8000-000000000006';
const UNRESOLVED_ACTOR_ROW_ID = 'aud-019e30c3-2c00-7200-8000-000000000007';

const PLANNING_NOTIFICATION_RUN_TARGET_THE_SCHEDULED_JOB_REALLY_WRITES = 'PlanningNotificationRun';
const REDACTED_SENTINEL_THE_REDACTOR_WRITES_FOR_AN_UNLISTED_ENTITY = '[redacted]';

const clubUsers: UserListItem[] = [
  {
    id: `usr-${ACTOR_USER_ID}`,
    username: ACTOR_USERNAME,
    friendlyName: 'Anna Meier',
    notificationEmail: 'anna.meier@example.test',
    roles: [],
    enabled: true,
    invitePending: false,
  },
];

const succeededRowsCarryNoHttpStatus: AuditEventRow[] = [
  {
    id: USER_ROW_ID,
    occurredAt: '2026-07-20T08:15:00Z',
    actorUserId: ACTOR_USER_ID,
    actorKeycloakSub: ACTOR_KEYCLOAK_SUB,
    actorKind: AuditEventRowActorKind.NORMAL,
    tenantClubId: CLUB_A_ID,
    action: AuditEventRowAction.UPDATE,
    targetEntityType: 'Aircraft',
    targetEntityId: 'ac-019e30c3-2c00-7300-8000-000000000001',
    beforeState: { callSign: 'HB-1234' },
    afterState: { callSign: 'HB-9999' },
    failed: false,
    systemActor: false,
  },
  {
    id: 'aud-019e30c3-2c00-7200-8000-000000000002',
    occurredAt: '2026-07-20T09:30:00Z',
    actorUserId: ACTOR_USER_ID,
    tenantClubId: CLUB_A_ID,
    action: AuditEventRowAction.CREATE,
    targetEntityType: 'Location',
    targetEntityId: 'loc-019e30c3-2c00-7400-8000-000000000001',
    afterState: { name: 'Birrfeld' },
    failed: false,
    systemActor: false,
  },
  {
    id: 'aud-019e30c3-2c00-7200-8000-000000000003',
    occurredAt: '2026-07-20T10:45:00Z',
    actorUserId: ACTOR_USER_ID,
    tenantClubId: CLUB_A_ID,
    action: AuditEventRowAction.DELETE,
    targetEntityType: 'FlightType',
    targetEntityId: 'ft-019e30c3-2c00-7500-8000-000000000001',
    beforeState: { code: 'SCHOOL' },
    failed: false,
    systemActor: false,
  },
];

const failedRowCarriesTheRecordedHttpStatus: AuditEventRow = {
  id: 'aud-019e30c3-2c00-7200-8000-000000000004',
  occurredAt: '2026-07-20T11:00:00Z',
  actorUserId: ACTOR_USER_ID,
  tenantClubId: CLUB_A_ID,
  action: AuditEventRowAction.DELETE,
  targetEntityType: 'Location',
  targetEntityId: 'loc-019e30c3-2c00-7400-8000-000000000099',
  failed: true,
  systemActor: false,
  httpStatus: 404,
};

const anonymousPublicRowCarriesNoActorIdentifierAtAll: AuditEventRow = {
  id: ANONYMOUS_ROW_ID,
  occurredAt: '2026-07-20T12:05:00Z',
  actorKind: AuditEventRowActorKind.ANONYMOUS_PUBLIC,
  tenantClubId: CLUB_A_ID,
  action: AuditEventRowAction.CREATE,
  targetEntityType: 'PublicFlightRegistration',
  targetEntityId: CLUB_A_ID,
  afterState: { kind: 'DISCOVERY_FLIGHT', clubId: CLUB_A_ID },
  failed: false,
  systemActor: false,
};

const scheduledPlanningNotificationJobRowCarriesNoActorIdentifierAtAll: AuditEventRow = {
  id: SCHEDULED_JOB_ROW_ID,
  occurredAt: '2026-07-20T13:10:00Z',
  actorKind: AuditEventRowActorKind.SYSTEM,
  tenantClubId: CLUB_A_ID,
  action: AuditEventRowAction.PLANNING_NOTIFICATIONS_RUN,
  targetEntityType: PLANNING_NOTIFICATION_RUN_TARGET_THE_SCHEDULED_JOB_REALLY_WRITES,
  targetEntityId: CLUB_A_ID,
  afterState: {
    clubId: REDACTED_SENTINEL_THE_REDACTOR_WRITES_FOR_AN_UNLISTED_ENTITY,
    imminentMailCount: REDACTED_SENTINEL_THE_REDACTOR_WRITES_FOR_AN_UNLISTED_ENTITY,
    weekAheadMailCount: REDACTED_SENTINEL_THE_REDACTOR_WRITES_FOR_AN_UNLISTED_ENTITY,
  },
  failed: false,
  systemActor: true,
};

const federatedActorRowResolvesToNoClubUser: AuditEventRow = {
  id: UNRESOLVED_ACTOR_ROW_ID,
  occurredAt: '2026-07-20T14:20:00Z',
  actorKeycloakSub: UNLISTED_ACTOR_KEYCLOAK_SUB,
  actorKind: AuditEventRowActorKind.NORMAL,
  tenantClubId: CLUB_A_ID,
  action: AuditEventRowAction.UPDATE,
  targetEntityType: 'Club',
  targetEntityId: CLUB_A_ID,
  afterState: { name: 'SG Birrfeld' },
  failed: false,
  systemActor: false,
};

const seedRows: AuditEventRow[] = [
  ...succeededRowsCarryNoHttpStatus,
  failedRowCarriesTheRecordedHttpStatus,
  anonymousPublicRowCarriesNoActorIdentifierAtAll,
  scheduledPlanningNotificationJobRowCarriesNoActorIdentifierAtAll,
  federatedActorRowResolvesToNoClubUser,
];

function setupAuditBackend(rows: AuditEventRow[]) {
  const pageSizeDefault = 50;
  return async (route: Route) => {
    const url = new URL(route.request().url());
    const p = url.searchParams;

    const action = p.get('action');
    const targetEntityType = p.get('targetEntityType');
    const occurredFrom = p.get('occurredFrom');
    const occurredTo = p.get('occurredTo');
    const pageSize = Number(p.get('pageSize') ?? pageSizeDefault);
    const pageOffset = Number(p.get('pageOffset') ?? 0);

    const filtered = rows.filter((r) => {
      if (action && r.action !== action) return false;
      if (targetEntityType && r.targetEntityType !== targetEntityType) return false;
      if (occurredFrom && r.occurredAt && r.occurredAt < occurredFrom) return false;
      if (occurredTo && r.occurredAt && r.occurredAt > occurredTo) return false;
      return true;
    });

    const window = filtered.slice(pageOffset, pageOffset + pageSize);
    const hasMore = pageOffset + pageSize < filtered.length;
    const body: AuditEventPage = {
      items: window,
      hasMore,
      nextOffset: hasMore ? pageOffset + pageSize : pageOffset + window.length,
    };
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  };
}

test('audit-logs: /system/logs lists mutation-audit rows with filters + row detail', async ({
  page,
}) => {
  const rows = seedRows.map((r) => ({ ...r }));
  await page.route('**/api/v1/admin/audit-events**', setupAuditBackend(rows));

  await page.goto('/system/logs');

  await expect(page.getByTestId('audit-logs-table')).toBeVisible();
  await expect(page.getByTestId('audit-row').first()).toBeVisible();

  await expect(page.getByTestId('audit-filter-action')).toBeVisible();
  await expect(page.getByTestId('audit-filter-target')).toBeVisible();
  await expect(page.getByTestId('audit-filter-from')).toBeVisible();
  await expect(page.getByTestId('audit-filter-to')).toBeVisible();
  await expect(page.getByTestId('audit-clear-filters')).toBeVisible();

  await expect(page.getByTestId('audit-pager-next')).toBeVisible();
  await page.getByTestId('audit-row').first().click();
  await expect(page.getByTestId('audit-row-detail')).toBeVisible();

  await page.screenshot({ path: 'screenshots/audit-logs/01-list-populated.png', fullPage: true });
});

test('audit-logs: the actor column tells an authenticated user, an anonymous public submission and a scheduled job apart', async ({
  page,
}) => {
  const rows = seedRows.map((r) => ({ ...r }));
  await page.route('**/api/v1/admin/audit-events**', setupAuditBackend(rows));
  await page.route('**/api/v1/users', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(clubUsers),
    }),
  );

  await page.goto('/system/logs');

  const user = page.locator(`[data-audit-id="${USER_ROW_ID}"]`);
  await expect(user.getByTestId('audit-row-actor')).toHaveText(ACTOR_USERNAME);

  const anonymousActorCell = page
    .locator(`[data-audit-id="${ANONYMOUS_ROW_ID}"]`)
    .getByTestId('audit-row-actor');
  await expect(anonymousActorCell).toHaveText(
    await labelInTheLocaleTheSessionRenders(
      page,
      (translations) => translations.auditLogs.actor.anonymousPublic,
    ),
  );

  const scheduledJobActorCell = page
    .locator(`[data-audit-id="${SCHEDULED_JOB_ROW_ID}"]`)
    .getByTestId('audit-row-actor');
  await expect(scheduledJobActorCell).toHaveText(
    await labelInTheLocaleTheSessionRenders(
      page,
      (translations) => translations.auditLogs.actor.system,
    ),
  );

  const anonymousCellAsRendered = (await anonymousActorCell.textContent())?.trim();
  const scheduledJobCellAsRendered = (await scheduledJobActorCell.textContent())?.trim();
  expect(anonymousCellAsRendered, 'the rendered anonymous cell is not empty').toBeTruthy();
  expect(
    anonymousCellAsRendered,
    'the screen renders the anonymous submission and the scheduled job as different text',
  ).not.toBe(scheduledJobCellAsRendered);

  const federated = page.locator(`[data-audit-id="${UNRESOLVED_ACTOR_ROW_ID}"]`);
  await expect(federated.getByTestId('audit-row-actor')).toHaveText(UNLISTED_ACTOR_KEYCLOAK_SUB);

  await page.screenshot({ path: 'screenshots/audit-logs/02-anonymous-actor.png', fullPage: true });
});
