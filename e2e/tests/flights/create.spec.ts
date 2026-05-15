// Spec #04: drive the glider-flight "new" form end-to-end and assert
// the row shows up in the today-filtered list. Aircraft + Pilot are
// selectize widgets, so we mutate $scope directly (see TEST_WRITING.md §6).
//
// Contract gaps (TODO testid): `flights-new-button` on the "+" toolbar
// button, `flight-form` on the <form>, `flight-save` on the SAVE button.

import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { withPool } from '../../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const FLIGHTS_LIST = '/flights';
const SECONDARY_TIMEOUT = 15_000;

async function waitForFlightFormReady(page: Page): Promise<void> {
  // #FlightDate is a <fls-date-picker> directive, not an <input> — match any element.
  await page.locator('#FlightDate').waitFor({ state: 'visible', timeout: SECONDARY_TIMEOUT });
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });
  // Wait for $scope.gliderAircrafts + gliderPilots to hydrate.
  await page.waitForFunction(() => {
    const w = window as unknown as { angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } } };
    if (!w.angular) return false;
    const formEl = document.querySelector('form[name="flightDetailsForm"]');
    if (!formEl) return false;
    const s = w.angular.element(formEl).scope() as {
      gliderAircrafts?: unknown[];
      gliderPilots?: unknown[];
      flightDetails?: { GliderFlightDetailsData?: unknown };
    };
    return Array.isArray(s.gliderAircrafts) && s.gliderAircrafts.length > 0
      && Array.isArray(s.gliderPilots) && s.gliderPilots.length > 0
      && !!s.flightDetails && !!s.flightDetails.GliderFlightDetailsData;
  }, undefined, { timeout: SECONDARY_TIMEOUT });
}

test('flights:create new glider flight via UI shows up in list', async ({ loggedInPage }, testInfo) => {
  const id = testId(testInfo);
  const uniqueComment = id.name;

  // Pre-clean prior-run row (Comment isn't unique in the schema).
  await withPool(async (pool) => {
    await pool.request()
      .input('comment', sql.NVarChar, uniqueComment)
      .query('DELETE FROM Flights WHERE Comment = @comment');
  });

  await gotoRoute(loggedInPage, FLIGHTS_LIST);

  const newButton = loggedInPage.locator('.fls-new-button button:has(span.fa-plus)');
  await expect(newButton, 'new-flight toolbar button must be visible on /flights').toBeVisible();
  await newButton.click();
  await loggedInPage.waitForURL(/#\/flights\/new$/, { timeout: SECONDARY_TIMEOUT });
  await waitForFlightFormReady(loggedInPage);

  // Inject the form values directly on the AngularJS scope (selectize widgets
  // are hostile to Playwright — see TEST_WRITING.md §6).
  const flightInjection = await loggedInPage.evaluate((comment) => {
    const w = window as unknown as {
      angular: {
        element: (n: Element) => {
          scope: () => Record<string, unknown> & {
            flightDetails: {
              FlightDate?: Date | string;
              StartType?: string | number;
              GliderFlightDetailsData: Record<string, unknown>;
              TowFlightDetailsData?: Record<string, unknown> | null;
            };
            gliderAircrafts: Array<{ AircraftId: string; Immatriculation: string; NrOfSeats: number; HasEngine?: boolean }>;
            gliderPilots: Array<{ PersonId: string; Lastname: string }>;
            gliderFlightTypes: Array<{ FlightTypeId: string; FlightCode: string; IsPassengerFlight?: boolean; InstructorRequired?: boolean; ObserverPilotOrInstructorRequired?: boolean }>;
            times: { gliderStart: string; gliderLanding: string };
            gliderAircraftSelectionChanged: (reset?: boolean) => void;
            flightTypeChanged: () => void;
            startTypeChanged: () => void;
            formatGliderStart: () => void;
            formatGliderLanding: () => void;
            $apply: () => void;
          };
        };
      };
    };
    const formEl = document.querySelector('form[name="flightDetailsForm"]');
    if (!formEl) throw new Error('flightDetailsForm not found');
    const ngEl = w.angular.element(formEl);
    const s = ngEl.scope();

    // 2-seater no-engine glider skips the engine-counter required block.
    const glider = s.gliderAircrafts.find(a => a.Immatriculation === 'HB-3407')
      ?? s.gliderAircrafts.find(a => a.NrOfSeats >= 2 && !a.HasEngine)
      ?? s.gliderAircrafts[0];
    if (!glider) throw new Error('no glider aircraft seeded');

    // Non-pax / non-instructor flight type keeps conditional required fields hidden.
    const flightType = s.gliderFlightTypes.find(t =>
      !t.IsPassengerFlight && !t.InstructorRequired && !t.ObserverPilotOrInstructorRequired,
    ) ?? s.gliderFlightTypes[0];

    const pilot = s.gliderPilots[0];
    if (!pilot) throw new Error('no glider pilot seeded');

    s.flightDetails.FlightDate = new Date();
    s.flightDetails.StartType = '3'; // Self-launch — no tow plane required.
    // Top-level Comment too — pre-clean / assertion query uses Flights.Comment.
    (s.flightDetails as Record<string, unknown>).Comment = comment;

    const gld = s.flightDetails.GliderFlightDetailsData;
    gld.AircraftId = glider.AircraftId;
    gld.PilotPersonId = pilot.PersonId;
    if (flightType) gld.FlightTypeId = flightType.FlightTypeId;
    gld.NrOfLdgs = 1;
    gld.FlightComment = comment;
    gld.IsSoloFlight = true;

    s.times.gliderStart = '10:00';
    s.times.gliderLanding = '10:30';
    s.flightDetails.TowFlightDetailsData = {};

    s.startTypeChanged();
    s.gliderAircraftSelectionChanged(false);
    s.flightTypeChanged();
    s.formatGliderStart();
    s.formatGliderLanding();
    s.$apply();

    return {
      aircraft: glider.Immatriculation,
      pilot: pilot.Lastname,
      flightTypeCode: flightType?.FlightCode ?? null,
    };
  }, uniqueComment);

  test.info().annotations.push({
    type: 'fixture-selection',
    description: `glider=${flightInjection.aircraft} pilot=${flightInjection.pilot} flightCode=${flightInjection.flightTypeCode}`,
  });

  const submitButton = loggedInPage.locator('form[name="flightDetailsForm"] button[type="submit"]').first();
  await expect(submitButton, 'SAVE button should be enabled once the form is valid').toBeEnabled({ timeout: SECONDARY_TIMEOUT });
  await submitButton.click();

  await loggedInPage.waitForURL(/#\/flights(\?|$)/, { timeout: SECONDARY_TIMEOUT });
  await loggedInPage.waitForLoadState('domcontentloaded');
  await loggedInPage.waitForTimeout(500);
  await loggedInPage.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });

  // Primary assertion: flight persisted to DB. Going via SQL is immune to
  // today-filter timezone slip and ng-table refresh races.
  const inserted = await withPool(async (pool) => {
    const r = await pool.request()
      .input('comment', sql.NVarChar, uniqueComment)
      .query('SELECT COUNT(*) AS Cnt FROM Flights WHERE Comment = @comment');
    return r.recordset[0].Cnt as number;
  });
  expect(inserted, 'flight should be persisted in DB after form submit').toBeGreaterThan(0);

  // Secondary: list should also surface the new row (may lag under load).
  await expect(async () => {
    const count = await loggedInPage.locator(`tbody [data-testid="row"]:has-text("${uniqueComment}")`).count();
    expect(count).toBeGreaterThan(0);
  }).toPass({ timeout: SECONDARY_TIMEOUT });

  await screenshot(loggedInPage, 'create-01');
});
