import { existsSync } from "node:fs";

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";

test.use({ video: "on" });

const ADMIN = { username: "testclubadmin", password: "s" } as const;

test.setTimeout(180_000);

test("J-6 parity: legacy planning list, plus best-effort edit form + setup wizard (parity video)", async ({
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

    await gotoRoute(page, "/planning");
    const firstRow = page.locator('tr[data-testid="row"]').first();
    await firstRow.waitFor({ state: "visible", timeout: 30_000 });
    const firstDate = firstRow.locator("td").first();
    await firstDate.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstDate).not.toBeEmpty();
    await screenshot(page, "planning-parity-J6-01-legacy-list");

    await page.screenshot({
      path: testInfo.outputPath("legacy-planning-list.png"),
      fullPage: true,
    });

    try {
      await firstRow.click();
      await page.waitForFunction(
        () => /#\/planning\/[0-9a-fA-F-]{36}\/view/.test(window.location.hash),
        undefined,
        { timeout: 30_000 },
      );
      const id = await page.evaluate(() => {
        const m = window.location.hash.match(
          /#\/planning\/([0-9a-fA-F-]{36})\/view/,
        );
        return m ? m[1] : null;
      });
      expect(id, "expected a planning-day id in the /view URL").toBeTruthy();
      await gotoRoute(page, `/planning/${id}/edit`);
      const editForm = page.locator('form[name="planningForm"]');
      await editForm.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "planning-parity-J6-02-legacy-form");
      await page.screenshot({
        path: testInfo.outputPath("legacy-planning-form.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-6] legacy planning-edit form capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    try {
      await gotoRoute(page, "/planningsetup");
      const setupStart = page.locator("#startdate");
      await setupStart.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "planning-parity-J6-03-legacy-setup");
      await page.screenshot({
        path: testInfo.outputPath("legacy-planning-setup.png"),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-6] legacy planning setup-wizard capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    expect(
      existsSync(testInfo.outputPath("legacy-planning-list.png")),
      "expected legacy parity screenshot legacy-planning-list.png in the test " +
        "output dir — the fanout gallery's J-6 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [
      "legacy-planning-form.png",
      "legacy-planning-setup.png",
    ]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-6] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the list pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
