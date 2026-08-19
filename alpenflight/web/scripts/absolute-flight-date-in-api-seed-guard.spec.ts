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
  findSeedingCallArgumentSpans: (source: string) => { start: number; end: number }[];
  findAbsoluteDateSeedsInApiSeeds: (source: string) => {
    line: number;
    column: number;
    field: string;
    value: string;
    insideTheSeedingCall: boolean;
  }[];
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

const SEED_WHOSE_ABSOLUTE_DATE_HIDES_INSIDE_A_BACKTICK_TEMPLATE_LITERAL = `
import { test } from '@playwright/test';

test('seeds a flight through backtick-quoted dates', async ({ request }) => {
  await request.post('/api/v1/flights', {
    data: {
      flightDate: \`2026-05-15\`,
      startDateTime: \`2026-05-15T08:00:00Z\`,
      ldgDateTime: \`2026-05-15T09:30:00Z\`,
    },
  });
});
`;

const SEED_WHOSE_TEMPLATE_LITERAL_INTERPOLATES_A_DERIVED_DATE = `
import { test } from '@playwright/test';
import { daysAgo } from './_helpers/seed-flight-date';

test('seeds a flight relative to the run date', async ({ request }) => {
  await request.post('/api/v1/flights', {
    data: {
      flightDate: \`\${daysAgo(30)}\`,
      startDateTime: \`\${daysAgo(30)}T08:00:00Z\`,
      ldgDateTime: \`\${daysAgo(30)}T09:30:00Z\`,
    },
  });
});
`;

const SEED_WHOSE_FIELD_NAME_IS_A_QUOTED_JSON_STYLE_KEY = `
import { test } from '@playwright/test';

test('seeds a flight from a JSON-shaped body', async ({ request }) => {
  await request.post('/api/v1/flights', {
    data: JSON.parse('{ "flightDate": "2026-05-15" }'),
  });
});
`;

const SEED_BODY_HOISTED_TO_A_CONST_OUTSIDE_THE_CALL = `
import { test } from '@playwright/test';

const FLIGHT_SEED_BODY = {
  flightAircraftType: 'GLIDER',
  flightDate: '2026-05-15',
  startDateTime: '2026-05-15T08:00:00Z',
};

test('seeds a flight from a hoisted body', async ({ request }) => {
  await request.post('/api/v1/flights', { data: FLIGHT_SEED_BODY });
});
`;

const SEED_EXPRESSED_AS_A_PUT = `
import { test } from '@playwright/test';

test('replaces a flight', async ({ request }) => {
  await request.put('/api/v1/flights/1', {
    data: { flightDate: '2026-05-15', startDateTime: '2026-05-15T08:00:00Z' },
  });
});
`;

const SEED_EXPRESSED_AS_A_PATCH = `
import { test } from '@playwright/test';

test('moves a flight', async ({ request }) => {
  await request.patch('/api/v1/flights/1', {
    data: { ldgDateTime: '2026-05-15T09:30:00Z' },
  });
});
`;

const SEED_EXPRESSED_AS_A_FETCH_WITH_AN_EXPLICIT_PUT_METHOD = `
import { test } from '@playwright/test';

test('replaces a flight through fetch', async ({ request }) => {
  await request.fetch('/api/v1/flights/1', {
    method: 'PUT',
    data: { flightDate: '2026-05-15' },
  });
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
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(SPEC_WITH_A_PLANTED_ABSOLUTE_DATE);

    expect(findings.map((f) => f.field)).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
    expect(findings.map((f) => f.value)).toEqual([
      '2026-05-15',
      '2026-05-15T08:00:00Z',
      '2026-05-15T09:30:00Z',
    ]);
  });

  it('greens once the same seed derives the date from the run date', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();

    expect(
      findAbsoluteDateSeedsInApiSeeds(SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE),
    ).toEqual([]);
  });

  it('ignores an absolute date in a mocked response body, which no server window can expire', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();

    expect(
      findAbsoluteDateSeedsInApiSeeds(MOCK_LANE_SPEC_THAT_STUBS_A_RESPONSE_INSTEAD_OF_SEEDING),
    ).toEqual([]);
  });

  it('still finds the seed when a regex literal and an apostrophe precede it', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SPEC_WHOSE_REGEX_AND_APOSTROPHE_COULD_DERAIL_A_NAIVE_SCANNER,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a seed that a fetch call expresses with an explicit POST method', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SEED_EXPRESSED_AS_A_FETCH_WITH_AN_EXPLICIT_POST_METHOD,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a fetch whose method is a variable, because that variable can hold POST', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SEED_EXPRESSED_AS_A_FETCH_WHOSE_METHOD_IS_A_VARIABLE,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a date a backtick template literal holds, the quote style the rule text recommends', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SEED_WHOSE_ABSOLUTE_DATE_HIDES_INSIDE_A_BACKTICK_TEMPLATE_LITERAL,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
    expect(findings.map((f) => f.value)).toEqual([
      '2026-05-15',
      '2026-05-15T08:00:00Z',
      '2026-05-15T09:30:00Z',
    ]);
  });

  it('greens on a template literal that interpolates a derived date, which is the fix shape', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();

    expect(
      findAbsoluteDateSeedsInApiSeeds(SEED_WHOSE_TEMPLATE_LITERAL_INTERPOLATES_A_DERIVED_DATE),
    ).toEqual([]);
  });

  it('reds on a quoted JSON-style key, which carries the same field under another spelling', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SEED_WHOSE_FIELD_NAME_IS_A_QUOTED_JSON_STYLE_KEY,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('reds on a request body hoisted to a const outside the call, and names it outside', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(SEED_BODY_HOISTED_TO_A_CONST_OUTSIDE_THE_CALL);

    expect(findings.map((f) => f.field)).toEqual(['flightDate', 'startDateTime']);
    expect(findings.every((f) => f.insideTheSeedingCall)).toBe(false);
  });

  it('reds on a seed a put call carries', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(SEED_EXPRESSED_AS_A_PUT);

    expect(findings.map((f) => f.field)).toEqual(['flightDate', 'startDateTime']);
    expect(findings.every((f) => f.insideTheSeedingCall)).toBe(true);
  });

  it('reds on a seed a patch call carries', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(SEED_EXPRESSED_AS_A_PATCH);

    expect(findings.map((f) => f.field)).toEqual(['ldgDateTime']);
  });

  it('reds on a seed that a fetch call expresses with an explicit PUT method', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
      SEED_EXPRESSED_AS_A_FETCH_WITH_AN_EXPLICIT_PUT_METHOD,
    );

    expect(findings.map((f) => f.field)).toEqual(['flightDate']);
  });

  it('leaves a GET fetch and a method-less fetch alone, so the widening does not over-fire', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();

    expect(findAbsoluteDateSeedsInApiSeeds(READ_ONLY_FETCH_THAT_CANNOT_SEED_ANYTHING)).toEqual([]);
  });

  it('reds on the root e2e suite legacy PascalCase spelling of the same three fields', async () => {
    const { findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const findings = findAbsoluteDateSeedsInApiSeeds(
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
    expect(violations.map((v) => v.line)).toEqual([8, 9, 10]);
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
    expect(RULE_EXPLANATION).toContain("'POST', 'PUT' or 'PATCH'");
    expect(GUARDED_DATE_FIELDS).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
  });

  it('recommends only a form it can verify, and states the limit it cannot see past', async () => {
    const { RULE_EXPLANATION, findAbsoluteDateSeedsInApiSeeds } = await loadGuard();
    const recommendedTemplateLiteral = RULE_EXPLANATION.slice(
      RULE_EXPLANATION.indexOf('`${flightDate}'),
    ).split('`')[1];

    expect(
      findAbsoluteDateSeedsInApiSeeds(
        `await request.post('/api/v1/flights', { data: { startDateTime: \`${recommendedTemplateLiteral}\` } });`,
      ),
    ).toEqual([]);
    expect(RULE_EXPLANATION).toContain('LIMIT: the guard reads no data flow.');
  });
});
