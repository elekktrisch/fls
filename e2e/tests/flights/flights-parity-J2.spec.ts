import { existsSync } from "node:fs";
import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  waitForBusyIndicatorsToClear,
  screenshot,
} from "../../fixtures";

test.use({ video: "on" });

const SEEDED_TESTCLUB_ADMIN = {
  username: "testclubadmin",
  password: "s",
} as const;

test.setTimeout(120_000);

test("@quarantine-legacy J-2 parity: legacy flight list (glider+tow) + form + motor air-movements (read-only parity video)", async ({
  browser,
}, testInfo) => {
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    recordVideo: {
      dir: testInfo.outputPath("video"),
      size: { width: 1280, height: 800 },
    },
  });
  const page = await ctx.newPage();

  const aerotowGliderRow = page
    .locator('tr[data-testid="row"]', {
      has: page.locator(
        'td.immatriculation[ng-bind="flight.Immatriculation"]',
        { hasText: "HB-3407" },
      ),
    })
    .first();

  try {
    await loginViaUi(
      page,
      SEEDED_TESTCLUB_ADMIN.username,
      SEEDED_TESTCLUB_ADMIN.password,
    );
    await waitForLoggedInState(page);

    await test.step("1. LIST: the seeded glider list at /flights (glider + paired tow), read-only", async () => {
      await gotoRoute(page, "/flights");
      await expect
        .poll(async () => aerotowGliderRow.count(), {
          message:
            "expected the seeded HB-3407 aerotow row in the default (today) list",
          timeout: 30_000,
        })
        .toBeGreaterThanOrEqual(1);
      const firstImmat = page
        .locator(
          'tr[data-testid="row"] td.immatriculation[ng-bind="flight.Immatriculation"]',
        )
        .first();
      await expect(firstImmat).toBeVisible({ timeout: 30_000 });
      await expect(firstImmat).not.toBeEmpty();
      await screenshot(page, "flights-parity-J2-01-legacy-list");

      await expect(aerotowGliderRow).toBeVisible({ timeout: 30_000 });

      await page.screenshot({
        path: testInfo.outputPath("legacy-flight-list.png"),
        fullPage: true,
      });
    });

    await test.step("2. FORM: open the HB-3407 aerotow row — its glider + tow field set is the parity surface", async () => {
      await aerotowGliderRow.click();
      await expect
        .poll(() => new URL(page.url()).hash, { timeout: 30_000 })
        .toMatch(/#\/flights\/[0-9a-f-]{36}$/i);
      const flightDate = page.locator("#FlightDate");
      await flightDate.waitFor({ state: "visible", timeout: 30_000 });

      await waitForBusyIndicatorsToClear(page);
      const flightDateValueBoundOnlyOnceTheFlightDataLoaded =
        page.locator("#FlightDate input");
      await expect(flightDateValueBoundOnlyOnceTheFlightDataLoaded).toHaveValue(
        /.+/,
        { timeout: 5_000 },
      );
      await screenshot(page, "flights-parity-J2-02-legacy-form-top");

      await page.screenshot({
        path: testInfo.outputPath("legacy-flight-form.png"),
        fullPage: true,
      });

      const towFieldSet = page.locator(
        'fls-flight-edit-tow-form div[ng-if="needsTowplane(flightDetails.StartType)"]',
      );
      await expect
        .poll(
          async () =>
            page.evaluate(() => {
              const form = document.querySelector(
                'form[name="flightDetailsForm"]',
              );
              if (!form) return null;
              const w = window as unknown as {
                angular?: {
                  element: (e: Element) => {
                    scope: () => { flightDetails?: { StartType?: unknown } };
                  };
                };
              };
              const scope = w.angular?.element(form).scope();
              const st = scope?.flightDetails?.StartType;
              return st === undefined || st === null ? null : String(st);
            }),
          {
            message:
              "expected the opened flight's flightDetails.StartType to bind to 1 (aerotow) before asserting the tow field set",
            timeout: 30_000,
          },
        )
        .toBe("1");
      await expect(towFieldSet).toBeVisible({ timeout: 15_000 });
      await towFieldSet.scrollIntoViewIfNeeded();
      await expect(
        page
          .locator(
            'fls-flight-edit-tow-form div[ng-if="needsTowplane(flightDetails.StartType)"] .selectize-input',
          )
          .first(),
      ).toBeVisible();
      await screenshot(page, "flights-parity-J2-03-legacy-form-fields");
    });

    await test.step("3. MOTOR: legacy's SEPARATE /airmovements screen, which AlpenFlight unifies into /flights", async () => {
      await gotoRoute(page, "/airmovements");
      const motorRows = page.locator('tr[data-testid="row"]');
      await expect
        .poll(async () => motorRows.count(), {
          message:
            "expected at least one seeded motor air-movement row in the default (today) list",
          timeout: 30_000,
        })
        .toBeGreaterThanOrEqual(1);
      const firstMotor = page
        .locator(
          'tr[data-testid="row"] td.immatriculation[ng-bind="flight.Immatriculation"]',
        )
        .first();
      await expect(firstMotor).toBeVisible({ timeout: 30_000 });
      await expect(firstMotor).not.toBeEmpty();
      await screenshot(page, "flights-parity-J2-04-legacy-airmovements");

      await page.screenshot({
        path: testInfo.outputPath("legacy-airmovements-list.png"),
        fullPage: true,
      });
    });
  } finally {
    await ctx.close();
  }

  for (const png of [
    "legacy-flight-list.png",
    "legacy-flight-form.png",
    "legacy-airmovements-list.png",
  ]) {
    const view = png.includes("airmovements")
      ? "motor"
      : png.includes("list")
        ? "list"
        : "form";
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fanout gallery's J-2 ${view} parity half depends on it`,
    ).toBeTruthy();
  }
});
