import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

const GUARD = resolve(__dirname, 'absolute-flight-date-in-api-seed-guard.mjs');

async function loadGuard(): Promise<{
  ALPENFLIGHT_E2E_TREE: string;
  LEGACY_E2E_TREE: string;
  DEFAULT_SCAN_ROOTS: string[];
  GUARDED_DATE_FIELDS: string[];
  RULE_EXPLANATION: string;
  findApiPostArgumentSpans: (source: string) => { start: number; end: number }[];
  findAbsoluteDateSeedsInApiPosts: (
    source: string,
  ) => { line: number; column: number; field: string; value: string }[];
  typeScriptFilesUnder: (root: string) => string[];
  scanTypeScriptTree: (
    root: string,
  ) => { file: string; line: number; field: string; value: string }[];
  scanEveryGuardedTree: (
    roots?: string[],
  ) => { file: string; line: number; field: string; value: string }[];
}> {
  return await import(pathToFileURL(GUARD).href);
}

const SPEC_WITH_A_PLANTED_ABSOLUTE_DATE = `
import { test } from '@playwright/test';

test('seeds a flight', async ({ request }) => {
  await request.post('/api/v1/flights', {
    data: {
      flightAircraftType: 'GLIDER',
      flightDate: '2026-05-15',
      startDateTime: '2026-05-15T08:00:00Z',
      ldgDateTime: '2026-05-15T09:30:00Z',
    },
  });
});
`;

const SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE = `
import { test } from '@playwright/test';
import { seededFlightDateInsideListWindowAndPastBillGate } from './_helpers/seed-flight-date';

test('seeds a flight', async ({ request }) => {
  const flightDate = seededFlightDateInsideListWindowAndPastBillGate();
  await request.post('/api/v1/flights', {
    data: {
      flightAircraftType: 'GLIDER',
      flightDate,
      startDateTime: \`\${flightDate}T08:00:00Z\`,
      ldgDateTime: \`\${flightDate}T09:30:00Z\`,
    },
  });
});
`;

const MOCK_LANE_SPEC_THAT_STUBS_A_RESPONSE_INSTEAD_OF_SEEDING = `
import { test } from '@playwright/test';

test('renders a flight row', async ({ page }) => {
  await page.route('**/api/v1/flights', (route) =>
    route.fulfill({
      status: 200,
      body: JSON.stringify([{ flightDate: '2026-05-20', startDateTime: '2026-05-20T08:00:00Z' }]),
    }),
  );
});
`;

const SPEC_WHOSE_REGEX_AND_APOSTROPHE_COULD_DERAIL_A_NAIVE_SCANNER = `
import { test } from '@playwright/test';

const strip = (id: string) => id.replace(/^fl-(\\(|\\))?/, '');

test("it's fine", async ({ request }) => {
  await request.post('/api/v1/flights', {
    data: { flightDate: '2026-05-15', note: strip('fl-1') },
  });
});
`;

const HELPER_MODULE_THAT_IS_NOT_A_SPEC_FILE_AND_SEEDS_AN_ABSOLUTE_DATE = `
import { type APIRequestContext } from '@playwright/test';

export async function seedReportingFixture(api: APIRequestContext, bearer: string) {
  await api.post('/api/v1/flights', {
    headers: { authorization: bearer },
    data: {
      flightDate: '2026-05-15',
      startDateTime: '2026-05-15T08:00:00Z',
      ldgDateTime: '2026-05-15T09:30:00Z',
    },
  });
}
`;

const SEED_EXPRESSED_AS_A_FETCH_WITH_AN_EXPLICIT_POST_METHOD = `
import { test } from '@playwright/test';

test('seeds a flight through fetch', async ({ request }) => {
  await request.fetch('/api/v1/flights', {
    method: 'POST',
    data: { flightDate: '2026-05-15' },
  });
});
`;

const SEED_EXPRESSED_AS_A_FETCH_WHOSE_METHOD_IS_A_VARIABLE = `
import { test } from '@playwright/test';

async function api(request, method: 'GET' | 'POST', body: unknown) {
  return request.fetch('/api/v1/flights', {
    method,
    data: { flightDate: '2026-05-15' },
  });
}
`;

const READ_ONLY_FETCH_THAT_CANNOT_SEED_ANYTHING = `
import { test } from '@playwright/test';

test('reads flights back', async ({ request }) => {
  await request.fetch('/api/v1/flights?flightDate=2026-05-15', {
    method: 'GET',
    data: { flightDate: '2026-05-15' },
  });
  await request.fetch('/api/v1/flights');
});
`;

const LEGACY_SEED_IN_THE_ROOT_E2E_TREE_USING_PASCAL_CASE_FIELDS = `
import { expect, test } from '../../fixtures';

test('creates a legacy flight', async ({ loggedInPage }) => {
  await loggedInPage.request.post('/api/v1/flights', {
    data: {
      FlightDate: '2026-05-15',
      FlightStates: { StartDateTime: '2026-05-15T10:00:00', LdgDateTime: '2026-05-15T10:30:00' },
    },
  });
  expect(true).toBe(true);
});
`;

function treeContaining(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), 'absolute-flight-date-guard-'));
  for (const [name, contents] of Object.entries(files)) {
    const full = join(root, name);
    mkdirSync(resolve(full, '..'), { recursive: true });
    writeFileSync(full, contents, 'utf8');
  }
  return root;
}

describe('absolute-flight-date-in-api-seed guard', () => {
  it('reds on a planted absolute date inside an API POST', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiPosts(SPEC_WITH_A_PLANTED_ABSOLUTE_DATE);

    expect(findings.map((f) => f.field)).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
    expect(findings[0].value).toBe('2026-05-15');
  });

  it('greens once the same seed derives the date from the run date', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();

    expect(
      findAbsoluteDateSeedsInApiPosts(SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE),
    ).toEqual([]);
  });

  it('ignores an absolute date in a mocked response body, which no server window can expire', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();

    expect(
      findAbsoluteDateSeedsInApiPosts(MOCK_LANE_SPEC_THAT_STUBS_A_RESPONSE_INSTEAD_OF_SEEDING),
    ).toEqual([]);
  });

  it('still finds the seed when a regex literal and an apostrophe precede it', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiPosts(
      SPEC_WHOSE_REGEX_AND_APOSTROPHE_COULD_DERAIL_A_NAIVE_SCANNER,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a seed that a fetch call expresses with an explicit POST method', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiPosts(
      SEED_EXPRESSED_AS_A_FETCH_WITH_AN_EXPLICIT_POST_METHOD,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a fetch whose method is a variable, because that variable can hold POST', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiPosts(
      SEED_EXPRESSED_AS_A_FETCH_WHOSE_METHOD_IS_A_VARIABLE,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('leaves a GET fetch and a method-less fetch alone, so the widening does not over-fire', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();

    expect(findAbsoluteDateSeedsInApiPosts(READ_ONLY_FETCH_THAT_CANNOT_SEED_ANYTHING)).toEqual([]);
  });

  it('reds on the root e2e suite legacy PascalCase spelling of the same three fields', async () => {
    const { findAbsoluteDateSeedsInApiPosts } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiPosts(
      LEGACY_SEED_IN_THE_ROOT_E2E_TREE_USING_PASCAL_CASE_FIELDS,
    );

    expect(findings.map((f) => f.field)).toEqual(['FlightDate', 'StartDateTime', 'LdgDateTime']);
  });

  it('reports the planted file and line when it walks a tree', async () => {
    const { scanTypeScriptTree } = await loadGuard();
    const root = treeContaining({
      'real-idp/planted.spec.ts': SPEC_WITH_A_PLANTED_ABSOLUTE_DATE,
      'real-idp/derived.spec.ts': SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE,
    });

    const violations = scanTypeScriptTree(root);

    expect(violations).toHaveLength(3);
    expect(violations.every((v) => v.file.endsWith('planted.spec.ts'))).toBe(true);
    expect(violations[0].line).toBe(8);
  });

  it('reds on a helper module that is not a spec file, which the old spec-only walk skipped', async () => {
    const { scanTypeScriptTree } = await loadGuard();
    const root = treeContaining({
      'real-idp/_helpers/reporting-parity-fixture.ts':
        HELPER_MODULE_THAT_IS_NOT_A_SPEC_FILE_AND_SEEDS_AN_ABSOLUTE_DATE,
    });

    const violations = scanTypeScriptTree(root);

    expect(violations).toHaveLength(3);
    expect(violations.every((v) => v.file.endsWith('reporting-parity-fixture.ts'))).toBe(true);
  });

  it('reds on a seed planted in the root e2e tree, which the old single-root walk never opened', async () => {
    const { scanEveryGuardedTree, ALPENFLIGHT_E2E_TREE } = await loadGuard();
    const plantedLegacyTree = treeContaining({
      'tests/flights/legacy-seed.spec.ts':
        LEGACY_SEED_IN_THE_ROOT_E2E_TREE_USING_PASCAL_CASE_FIELDS,
    });

    const violations = scanEveryGuardedTree([ALPENFLIGHT_E2E_TREE, plantedLegacyTree]);

    expect(violations.map((v) => v.field)).toEqual(['FlightDate', 'StartDateTime', 'LdgDateTime']);
  });

  it('greens over a tree once the planted date is removed', async () => {
    const { scanTypeScriptTree } = await loadGuard();
    const root = treeContaining({
      'real-idp/derived.spec.ts': SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE,
      'flights/mocked.spec.ts': MOCK_LANE_SPEC_THAT_STUBS_A_RESPONSE_INSTEAD_OF_SEEDING,
      'real-idp/_helpers/read-only.ts': READ_ONLY_FETCH_THAT_CANNOT_SEED_ANYTHING,
    });

    expect(scanTypeScriptTree(root)).toEqual([]);
  });

  it('scans both real e2e trees, opens helper files in each, and greens', async () => {
    const {
      scanEveryGuardedTree,
      typeScriptFilesUnder,
      DEFAULT_SCAN_ROOTS,
      ALPENFLIGHT_E2E_TREE,
      LEGACY_E2E_TREE,
    } = await loadGuard();

    expect(DEFAULT_SCAN_ROOTS).toEqual([ALPENFLIGHT_E2E_TREE, LEGACY_E2E_TREE]);
    expect(
      typeScriptFilesUnder(ALPENFLIGHT_E2E_TREE).some((f) =>
        f.endsWith('_helpers/reporting-parity-fixture.ts'),
      ),
    ).toBe(true);
    expect(
      typeScriptFilesUnder(LEGACY_E2E_TREE).some((f) => f.endsWith('tests/flights/create.spec.ts')),
    ).toBe(true);
    expect(typeScriptFilesUnder(LEGACY_E2E_TREE).some((f) => f.includes('node_modules'))).toBe(
      false,
    );
    expect(scanEveryGuardedTree()).toEqual([]);
  });

  it('reds loudly when a scanned tree moves, instead of silently covering nothing', async () => {
    const { scanEveryGuardedTree, ALPENFLIGHT_E2E_TREE } = await loadGuard();

    expect(() =>
      scanEveryGuardedTree([ALPENFLIGHT_E2E_TREE, resolve(ALPENFLIGHT_E2E_TREE, '../e2e-renamed')]),
    ).toThrow(/no longer covers its own inputs/);
  });

  it('names the derived-date fix in the message a failing developer reads', async () => {
    const { RULE_EXPLANATION, GUARDED_DATE_FIELDS } = await loadGuard();

    expect(RULE_EXPLANATION).toContain('seededFlightDateInsideListWindowAndPastBillGate');
    expect(RULE_EXPLANATION).toContain("method: 'POST'");
    expect(GUARDED_DATE_FIELDS).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
  });
});
