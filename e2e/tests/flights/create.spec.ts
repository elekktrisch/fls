
import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import { testId } from '../../test-id';
import { withPool } from '../../test-data';
import sql from 'mssql';
import type { Page } from '@playwright/test';

const FLIGHTS_LIST = '/flights';
const SECONDARY_TIMEOUT = 15_000;

async function waitForFlightFormReady(page: Page): Promise<void> {
  await page.locator('#FlightDate').waitFor({ state: 'visible', timeout: SECONDARY_TIMEOUT });
  await page.waitForFunction(() => {
    const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
    return spinners.every(el => {
      const rect = el.getBoundingClientRect();
      return rect.width === 0 && rect.height === 0;
    });
  }, undefined, { timeout: SECONDARY_TIMEOUT });
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

    const glider = s.gliderAircrafts.find(a => a.Immatriculation === 'HB-3407')
      ?? s.gliderAircrafts.find(a => a.NrOfSeats >= 2 && !a.HasEngine)
      ?? s.gliderAircrafts[0];
    if (!glider) throw new Error('no glider aircraft seeded');

    const flightType = s.gliderFlightTypes.find(t =>
      !t.IsPassengerFlight && !t.InstructorRequired && !t.ObserverPilotOrInstructorRequired,
    ) ?? s.gliderFlightTypes[0];

    const pilot = s.gliderPilots[0];
    if (!pilot) throw new Error('no glider pilot seeded');

    s.flightDetails.FlightDate = new Date();
    s.flightDetails.StartType = '3';
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

  const inserted = await withPool(async (pool) => {
    const r = await pool.request()
      .input('comment', sql.NVarChar, uniqueComment)
      .query('SELECT COUNT(*) AS Cnt FROM Flights WHERE Comment = @comment');
    return r.recordset[0].Cnt as number;
  });
  expect(inserted, 'flight should be persisted in DB after form submit').toBeGreaterThan(0);

  await expect(async () => {
    const count = await loggedInPage.locator(`tbody [data-testid="row"]:has-text("${uniqueComment}")`).count();
    expect(count).toBeGreaterThan(0);
  }).toPass({ timeout: SECONDARY_TIMEOUT });

  await screenshot(loggedInPage, 'create-01');
});
