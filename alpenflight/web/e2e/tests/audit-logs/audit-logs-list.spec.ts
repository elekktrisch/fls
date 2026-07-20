import { type Route } from '@playwright/test';
import { expect, test } from '../_helpers/console-guard';
import type { AuditEventPage } from '../../../src/app/api/generated/model/auditEventPage';
import type { AuditEventRow } from '../../../src/app/api/generated/model/auditEventRow';
import { AuditEventRowAction } from '../../../src/app/api/generated/model/auditEventRowAction';

/**
 * J-13 audit-log viewer (`/system/logs`) — mock inner-loop spec, committing the
 * SCREEN SHAPE. Mock-backend (`page.route`) over `GET /api/v1/admin/audit-events`;
 * the mock-auth principal carries CLUB_ADMINISTRATOR, so `page.goto('/system/logs')`
 * is the entry point.
 *
 * These assertions are THIN on purpose: they pin the stable data-testids the real
 * list / filter / row-detail components (T-03..T-06) will expose, so the shape is
 * contracted before the feature lands. The full flow (real mutation → live event,
 * filter round-trips, cursor pagination, before/after diff) is thickened by the
 * real-idp two-club gate spec (T-09). The route + page do not exist until T-03, so
 * this spec goes green once T-03..T-06 land — it must parse + collect now.
 */

const CLUB_A_ID = '019e30c3-2c00-7001-8000-000000000001';
const ACTOR_USER_ID = 'usr-019e30c3-2c00-7100-8000-000000000001';

const seedRows: AuditEventRow[] = [
  {
    id: 'aud-019e30c3-2c00-7200-8000-000000000001',
    occurredAt: '2026-07-20T08:15:00Z',
    actorUserId: ACTOR_USER_ID,
    tenantClubId: CLUB_A_ID,
    action: AuditEventRowAction.UPDATE,
    targetEntityType: 'Aircraft',
    targetEntityId: 'ac-019e30c3-2c00-7300-8000-000000000001',
    beforeState: { callSign: 'HB-1234' },
    afterState: { callSign: 'HB-9999' },
    failed: false,
    systemActor: false,
    httpStatus: 200,
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
    httpStatus: 201,
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
    httpStatus: 204,
  },
];

/**
 * Serve the `AuditEventPage` envelope, honouring the query filters the store
 * sends (`action`, `targetEntityType`, `occurredFrom`, `occurredTo`, `pageSize`,
 * `pageOffset`) so a filtered / paged request narrows the mock result exactly as
 * the real endpoint would. Single-page seed (`hasMore=false`) unless the offset
 * walks past the page.
 */
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

  // Table + at least one row rendered from the mocked page.
  await expect(page.getByTestId('audit-logs-table')).toBeVisible();
  await expect(page.getByTestId('audit-row').first()).toBeVisible();

  // Filter controls present (action / target / time-range + clear).
  await expect(page.getByTestId('audit-filter-action')).toBeVisible();
  await expect(page.getByTestId('audit-filter-target')).toBeVisible();
  await expect(page.getByTestId('audit-filter-from')).toBeVisible();
  await expect(page.getByTestId('audit-filter-to')).toBeVisible();
  await expect(page.getByTestId('audit-clear-filters')).toBeVisible();

  // Pager affordance + a row-detail region the expansion renders into.
  await expect(page.getByTestId('audit-pager-next')).toBeVisible();
  await page.getByTestId('audit-row').first().click();
  await expect(page.getByTestId('audit-row-detail')).toBeVisible();

  await page.screenshot({ path: 'screenshots/audit-logs/01-list-populated.png', fullPage: true });
});
