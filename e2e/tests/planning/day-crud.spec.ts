import { expect, gotoRoute, screenshot, test } from '../../fixtures';
import type { Page } from '@playwright/test';

const PLANNING_LIST = '/planning';
const TIMEOUT = 15_000;

async function countPlanningRows(page: Page): Promise<number> {
    return page.locator('tbody [data-testid="row"]').count();
}

async function waitForPlanningFormReady(page: Page): Promise<void> {
    await page.locator('form[name="planningForm"]').waitFor({ state: 'visible', timeout: TIMEOUT });
    await page.waitForFunction(() => {
        const spinners = Array.from(document.querySelectorAll('[data-testid="busy-indicator"]')) as HTMLElement[];
        return spinners.every(el => {
            const rect = el.getBoundingClientRect();
            return rect.width === 0 && rect.height === 0;
        });
    }, undefined, { timeout: TIMEOUT });
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

test('planning:create planning day with crew shows up in /planning list', async ({ loggedInPage }) => {

    await gotoRoute(loggedInPage, PLANNING_LIST);
    const baselineRows = await countPlanningRows(loggedInPage);

    const newButton = loggedInPage.locator('.fls-new-button button:has(span.fa-plus)');
    await expect(newButton, '"+" new-planning-day button must be visible on /planning').toBeVisible();
    await newButton.click();
    await loggedInPage.waitForURL(/#\/planning\/new\/edit$/, { timeout: TIMEOUT });
    await waitForPlanningFormReady(loggedInPage);

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

        const testClubHomebaseIcao = 'LSZK';
        const loc = s.md.locations.find(l => l.IcaoCode === testClubHomebaseIcao) ?? s.md.locations[0];
        const towPilot = s.md.towingPilots[0];
        const operator = s.md.gliderPilots[0];
        const instructor = s.md.instructors[0];

        s.planningDay.Day = new Date();
        s.planningDay.LocationId = loc.LocationId;
        s.planningDay.Remarks = remarks;
        s.planningDay.TowingPilotPersonId = towPilot.PersonId;
        s.planningDay.FlightOperatorPersonId = operator.PersonId;
        s.planningDay.InstructorPersonId = instructor.PersonId;
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

    const saveButton = loggedInPage.locator('form[name="planningForm"] button[type="submit"]').first();
    await expect(saveButton, 'SAVE button must enable once form is valid').toBeEnabled({ timeout: TIMEOUT });
    await saveButton.click();

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

    const afterRows = await countPlanningRows(loggedInPage);
    expect(afterRows, `expected planning list to grow by one (was ${baselineRows})`).toBe(baselineRows + 1);
    await expect(
        loggedInPage.locator(`tbody [data-testid="row"]:has-text("${uniqueRemarks}")`),
        'newly-created planning day row should be visible in /planning',
    ).toHaveCount(1);
  await screenshot(loggedInPage, 'day-crud-01');
});
