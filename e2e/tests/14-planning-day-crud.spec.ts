/**
 * Spec #14: planning-day-crud  (PLAN.md row #14)
 *
 * Drives the AngularJS planning-day "new" form end-to-end:
 *   1. Open /planning, count baseline rows in the today-onwards list.
 *   2. Click the "+" toolbar button -> /planning/new/edit.
 *   3. Fill date (today), location, remarks, and crew (TowingPilot,
 *      FlightOperator, Instructor) — the four masterdata pickers are all
 *      selectize widgets. Selectize is notoriously hostile to Playwright, so
 *      we mutate the AngularJS scope directly (same pattern as
 *      04-flights-create.spec.ts and 09-public-flows.spec.ts: the model
 *      binding IS the source of truth).
 *   4. Submit. The controller's $scope.save -> $scope.cancel chain navigates
 *      to /planning. We pick TODAY as the planning day's date so it appears
 *      on the default "from today" list filter (PlanningDaysController.js:13).
 *   5. Assert: row count went up by one, and the unique remarks string
 *      appears in a row (Remarks column on planning.html:42-47).
 *
 * Contract gaps (no template edits made — see SELECTORS.md "Note to the
 * rewrite team"):
 *   - TODO testid: `planning-new-button` on the "+" button in planning.html.
 *   - TODO testid: `planning-form` on the <form> in planning-edit.html.
 *   - TODO testid: `planning-save` on the SAVE button in planning-edit.html
 *     (currently located by `button[type="submit"]` inside the form).
 */

import { expect, gotoRoute, screenshot, test } from '../fixtures';
import type { Page } from '@playwright/test';

const PLANNING_LIST = '/planning';
const TIMEOUT = 15_000;

async function countPlanningRows(page: Page): Promise<number> {
    return page.locator('tbody [data-testid="row"]').count();
}

async function waitForPlanningFormReady(page: Page): Promise<void> {
    // The form's busy indicator is up while loadMasterData() (4 parallel
    // master-data fetches) and loadPlanningDay() are in flight.
    await page.locator('form[name="planningForm"]').waitFor({ state: 'visible', timeout: TIMEOUT });
    await page.waitForFunction(() => {
        const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
        return spinners.every(el => {
            const rect = el.getBoundingClientRect();
            return rect.width === 0 && rect.height === 0;
        });
    }, undefined, { timeout: TIMEOUT });
    // Wait until the controller's master-data dropdown lists are populated.
    await page.waitForFunction(() => {
        const w = window as unknown as { angular?: { element: (n: Element) => { scope: () => Record<string, unknown> } } };
        if (!w.angular) return false;
        const formEl = document.querySelector('form[name="planningForm"]');
        if (!formEl) return false;
        const s = w.angular.element(formEl).scope() as {
            md?: { locations?: unknown[]; gliderPilots?: unknown[]; towingPilots?: unknown[]; instructors?: unknown[] };
            planningDay?: { CanUpdateRecord?: boolean };
        };
        return !!s.md
            && Array.isArray(s.md.locations) && s.md.locations.length > 0
            && Array.isArray(s.md.gliderPilots) && s.md.gliderPilots.length > 0
            && Array.isArray(s.md.towingPilots) && s.md.towingPilots.length > 0
            && Array.isArray(s.md.instructors) && s.md.instructors.length > 0
            && !!s.planningDay && s.planningDay.CanUpdateRecord === true;
    }, undefined, { timeout: TIMEOUT });
}

test('planning:create planning day with crew shows up in /planning list', async ({ freshLoggedInPage: loggedInPage }) => {

    // 1. Baseline: open /planning and count today-onwards rows. The fixture
    //    seeds no planning days, but other tests in the worker may — so we
    //    diff rather than asserting an absolute count.
    await gotoRoute(loggedInPage, PLANNING_LIST);
    const baselineRows = await countPlanningRows(loggedInPage);

    // 2. Click "+" toolbar button (no testid — locate by `fa-plus` glyph in
    //    the toolbar, mirroring 04-flights-create.spec.ts).
    const newButton = loggedInPage.locator('.fls-new-button button:has(span.fa-plus)');
    await expect(newButton, '"+" new-planning-day button must be visible on /planning').toBeVisible();
    await newButton.click();
    await loggedInPage.waitForURL(/#\/planning\/new\/edit$/, { timeout: TIMEOUT });
    await waitForPlanningFormReady(loggedInPage);

    // 3. Inject form values directly onto the AngularJS scope. Picks the
    //    first seeded location + one person from each crew dropdown — these
    //    come from the regular FLSTest seeds (test-club pilots/instructors).
    const uniqueRemarks = `e2e-planning-${Date.now()}`;
    const selection = await loggedInPage.evaluate((remarks) => {
        const w = window as unknown as {
            angular: {
                element: (n: Element) => {
                    scope: () => {
                        planningDay: {
                            Day?: Date | string;
                            LocationId?: string;
                            Remarks?: string;
                            TowingPilotPersonId?: string;
                            FlightOperatorPersonId?: string;
                            InstructorPersonId?: string;
                            CanUpdateRecord?: boolean;
                        };
                        md: {
                            locations: Array<{ LocationId: string; LocationName: string; IcaoCode?: string }>;
                            gliderPilots: Array<{ PersonId: string; Lastname: string; Firstname: string }>;
                            towingPilots: Array<{ PersonId: string; Lastname: string; Firstname: string }>;
                            instructors: Array<{ PersonId: string; Lastname: string; Firstname: string }>;
                        };
                        $apply: () => void;
                    };
                };
            };
        };
        const formEl = document.querySelector('form[name="planningForm"]');
        if (!formEl) throw new Error('planningForm not found');
        const ngEl = w.angular.element(formEl);
        const s = ngEl.scope();

        // Prefer LSZK (the test club's home base, seeded in _test-fixture.sql),
        // fall back to the first location.
        const loc = s.md.locations.find(l => l.IcaoCode === 'LSZK') ?? s.md.locations[0];
        const towPilot = s.md.towingPilots[0];
        const operator = s.md.gliderPilots[0];
        const instructor = s.md.instructors[0];

        s.planningDay.Day = new Date();
        s.planningDay.LocationId = loc.LocationId;
        s.planningDay.Remarks = remarks;
        s.planningDay.TowingPilotPersonId = towPilot.PersonId;
        s.planningDay.FlightOperatorPersonId = operator.PersonId;
        s.planningDay.InstructorPersonId = instructor.PersonId;
        // `$apply` lives on the scope, not on the jqLite element wrapper.
        s.$apply();

        return {
            location: loc.IcaoCode ?? loc.LocationName,
            towPilot: towPilot.Lastname,
            operator: operator.Lastname,
            instructor: instructor.Lastname,
        };
    }, uniqueRemarks);

    test.info().annotations.push({
        type: 'fixture-selection',
        description: `loc=${selection.location} tow=${selection.towPilot} op=${selection.operator} instr=${selection.instructor}`,
    });

    // 4. Submit. No testid on the SAVE button — only the form's submit button.
    const saveButton = loggedInPage.locator('form[name="planningForm"] button[type="submit"]').first();
    await expect(saveButton, 'SAVE button must enable once form is valid').toBeEnabled({ timeout: TIMEOUT });
    await saveButton.click();

    // Controller's save() -> $scope.cancel() navigates to /planning.
    await loggedInPage.waitForURL(/#\/planning(\?|$)/, { timeout: TIMEOUT });
    await loggedInPage.waitForLoadState('domcontentloaded');
    await loggedInPage.waitForTimeout(500);
    await loggedInPage.waitForFunction(() => {
        const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
        return spinners.every(el => {
            const rect = el.getBoundingClientRect();
            return rect.width === 0 && rect.height === 0;
        });
    }, undefined, { timeout: TIMEOUT });

    // 5. Assert: row count grew by one AND the unique remarks text is in the list.
    const afterRows = await countPlanningRows(loggedInPage);
    expect(afterRows, `expected planning list to grow by one (was ${baselineRows})`).toBe(baselineRows + 1);
    await expect(
        loggedInPage.locator(`tbody [data-testid="row"]:has-text("${uniqueRemarks}")`),
        'newly-created planning day row should be visible in /planning',
    ).toHaveCount(1);
  await screenshot(loggedInPage, '14-planning-day-crud-01');
});
