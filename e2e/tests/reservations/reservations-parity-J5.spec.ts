import { existsSync } from "node:fs";

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";
import type { Page } from "@playwright/test";

test.use({ video: "on" });

const API_BASE = process.env.FLS_API ?? "http://localhost:25567";
const SETTINGS_KEY = "AircraftIdsToDisplayInScheduler";

const ADMIN = { username: "testclubadmin", password: "s" } as const;

const LEGACY_STACK_WALKTHROUGH_BUDGET_MS = 180_000;

// ext: alpenflight-proof-fanout.yml add_shot basenames
const GALLERY_LIST_PNG = "legacy-reservation-list.png";
const GALLERY_FORM_PNG = "legacy-reservation-form.png";
const GALLERY_SCHEDULER_PNG = "legacy-reservation-scheduler.png";

test.setTimeout(LEGACY_STACK_WALKTHROUGH_BUDGET_MS);

async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-loginResult");
    try {
      return raw ? (JSON.parse(raw).access_token as string) : null;
    } catch {
      return null;
    }
  });
  expect(token, "expected access_token in sessionStorage").toBeTruthy();
  return token!;
}

async function currentUserId(page: Page): Promise<string> {
  const userId = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-user");
    try {
      return raw ? (JSON.parse(raw).UserId as string) : null;
    } catch {
      return null;
    }
  });
  expect(userId, "expected UserId in sessionStorage").toBeTruthy();
  return userId!;
}

test("J-5 parity: legacy reservation list + edit form (+ scheduler) (parity video)", async ({
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

    await gotoRoute(page, "/reservations");
    const rows = page.locator('tr[data-testid="row"]');
    await expect
      .poll(async () => rows.count(), {
        message: "expected at least one seeded reservation row",
        timeout: 30_000,
      })
      .toBeGreaterThanOrEqual(1);
    const firstImmat = page
      .locator(
        'tr[data-testid="row"] td[ng-bind="reservation.Immatriculation"]',
      )
      .first();
    await firstImmat.waitFor({ state: "visible", timeout: 30_000 });
    await expect(firstImmat).not.toBeEmpty();
    await screenshot(page, "reservations-parity-J5-01-legacy-list");

    await page.screenshot({
      path: testInfo.outputPath(GALLERY_LIST_PNG),
      fullPage: true,
    });

    try {
      await firstImmat.click();
      const editForm = page.locator("form").first();
      await editForm.waitFor({ state: "visible", timeout: 30_000 });
      await screenshot(page, "reservations-parity-J5-02-legacy-form");
      await page.screenshot({
        path: testInfo.outputPath(GALLERY_FORM_PNG),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-5] legacy reservation-edit form capture skipped (slow/absent form): ${
          (err as Error).message
        }`,
      );
    }

    try {
      const token = await bearer(page);
      const userId = await currentUserId(page);
      const authHeader = { Authorization: `Bearer ${token}` };
      const overview = await page.request.get(
        `${API_BASE}/api/v1/aircrafts/overview`,
        {
          headers: authHeader,
        },
      );
      if (overview.ok()) {
        const aircrafts = (await overview.json()) as Array<{
          AircraftId: string;
        }>;
        const aircraftIds = aircrafts.map((a) => a.AircraftId);
        await page.request.post(`${API_BASE}/api/v1/settings`, {
          headers: authHeader,
          data: {
            UserId: userId,
            SettingKey: SETTINGS_KEY,
            SettingValue: JSON.stringify(aircraftIds),
          },
        });
      }
      await gotoRoute(page, "/reservation-scheduler");
      await page
        .locator(".scroll-container .container")
        .first()
        .waitFor({ state: "visible", timeout: 45_000 });
      await screenshot(page, "reservations-parity-J5-03-legacy-scheduler");
      await page.screenshot({
        path: testInfo.outputPath(GALLERY_SCHEDULER_PNG),
        fullPage: true,
      });
    } catch (err) {
      console.warn(
        `[J-5] legacy reservation-scheduler capture skipped (slow/absent grid): ${
          (err as Error).message
        }`,
      );
    }

    expect(
      existsSync(testInfo.outputPath(GALLERY_LIST_PNG)),
      `expected legacy parity screenshot ${GALLERY_LIST_PNG} in the test ` +
        "output dir — the fanout gallery's J-5 legacy half depends on it",
    ).toBeTruthy();
    for (const png of [GALLERY_FORM_PNG, GALLERY_SCHEDULER_PNG]) {
      if (!existsSync(testInfo.outputPath(png))) {
        console.warn(
          `[J-5] best-effort legacy parity screenshot ${png} absent (slow/flaky ` +
            "legacy stack) — the gallery drops that one entry, the list pair stands",
        );
      }
    }
  } finally {
    await page.close();
    await ctx.close();
  }
});
