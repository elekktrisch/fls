import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { describe, expect, it } from 'vitest';

const GUARD = resolve(__dirname, 'absolute-flight-date-in-api-seed-guard.mjs');

async function loadGuard(): Promise<{
  DEFAULT_SCAN_ROOT: string;
  GUARDED_DATE_FIELDS: string[];
  RULE_EXPLANATION: string;
  findApiPostArgumentSpans: (source: string) => { start: number; end: number }[];
  findAbsoluteDateSeedsInApiPosts: (
    source: string,
  ) => { line: number; column: number; field: string; value: string }[];
  scanSpecTree: (root?: string) => { file: string; line: number; field: string; value: string }[];
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

function specTreeContaining(files: Record<string, string>): string {
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

  it('reports the planted file and line when it walks a spec tree', async () => {
    const { scanSpecTree } = await loadGuard();
    const root = specTreeContaining({
      'real-idp/planted.spec.ts': SPEC_WITH_A_PLANTED_ABSOLUTE_DATE,
      'real-idp/derived.spec.ts': SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE,
    });

    const violations = scanSpecTree(root);

    expect(violations).toHaveLength(3);
    expect(violations.every((v) => v.file.endsWith('planted.spec.ts'))).toBe(true);
    expect(violations[0].line).toBe(8);
  });

  it('greens over a spec tree once the planted date is removed', async () => {
    const { scanSpecTree } = await loadGuard();
    const root = specTreeContaining({
      'real-idp/derived.spec.ts': SAME_SPEC_WITH_THE_DATE_DERIVED_FROM_THE_RUN_DATE,
      'flights/mocked.spec.ts': MOCK_LANE_SPEC_THAT_STUBS_A_RESPONSE_INSTEAD_OF_SEEDING,
    });

    expect(scanSpecTree(root)).toEqual([]);
  });

  it('greens over the real e2e spec tree', async () => {
    const { scanSpecTree, DEFAULT_SCAN_ROOT } = await loadGuard();

    expect(scanSpecTree(DEFAULT_SCAN_ROOT)).toEqual([]);
  });

  it('names the derived-date fix in the message a failing developer reads', async () => {
    const { RULE_EXPLANATION, GUARDED_DATE_FIELDS } = await loadGuard();

    expect(RULE_EXPLANATION).toContain('seededFlightDateInsideListWindowAndPastBillGate');
    expect(GUARDED_DATE_FIELDS).toEqual(['flightDate', 'startDateTime', 'ldgDateTime']);
  });
});
