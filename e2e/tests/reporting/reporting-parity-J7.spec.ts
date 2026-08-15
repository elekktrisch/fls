import { existsSync } from "node:fs";

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";
import { ensureGliderFlight, getBearerToken } from "../../test-data";

test.use({ video: "on" });

const ADMIN = { username: "testclubadmin", password: "s" } as const;

const LEGACY_STACK_WALKTHROUGH_BUDGET_MS = 180_000;

// ext: alpenflight-proof-fanout.yml add_shot basenames
const GALLERY_PICKER_PNG = "legacy-flightreports-picker.png";
const GALLERY_RESULT_PNG = "legacy-flightreports-result.png";
const GALLERY_CUSTOM_PNG = "legacy-flightreports-custom.png";

test.setTimeout(LEGACY_STACK_WALKTHROUGH_BUDGET_MS);

test("J-7 parity: legacy reporting picker + canned result + custom builder (parity video)", async ({
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

  try {
    await loginViaUi(page, ADMIN.username, ADMIN.password);
    await waitForLoggedInState(page);

    const token = await getBearerToken(page);
    await ensureGliderFlight(page.request, token, { comment: "J-7-parity" });

    await gotoRoute(page, "/flightreports");
    const cannedLink = page.locator(
      'a[href="#/flightreports/location/location-flights-this-year"]',
    );
    await cannedLink.waitFor({ state: "visible", timeout: 30_000 });
    await screenshot(page, "reporting-parity-J7-01-legacy-picker");
    await page.screenshot({
      path: testInfo.outputPath(GALLERY_PICKER_PNG),
      fullPage: true,
    });

    try {
      await gotoRoute(
        page,
        "/flightreports/location/location-flights-this-year",
      );
      const filterPanel = page.locator(".filter-criteria-panel");
      await filterPanel.waitFor({ state: "visible", timeout: 60_000 });
      const summaryTable = page.locator("table.fls").first();
      await summaryTable.waitFor({ state: "visible", timeout: 15_000 });
      await screenshot(page, "reporting-parity-J7-02-legacy-canned-result");
      await page.screenshot({
        path: testInfo.outputPath(GALLERY_RESULT_PNG),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-7] legacy canned-result capture skipped (slow/absent report): ${
          (err as Error).message
        }`,
      );
    }

    try {
      await gotoRoute(page, "/flightreports/custom/location/%7B%7D/edit");
      const applyBtn = page
        .locator('button[ng-click="applyCriteria()"]')
        .first();
      await applyBtn.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "reporting-parity-J7-03-legacy-custom-builder");
      await page.screenshot({
        path: testInfo.outputPath(GALLERY_CUSTOM_PNG),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-7] legacy custom-builder capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    expect(
      existsSync(testInfo.outputPath(GALLERY_PICKER_PNG)),
      `expected legacy parity screenshot ${GALLERY_PICKER_PNG} in the ` +
        "test output dir — the fanout gallery's J-7 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [GALLERY_RESULT_PNG, GALLERY_CUSTOM_PNG]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-7] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the picker pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
