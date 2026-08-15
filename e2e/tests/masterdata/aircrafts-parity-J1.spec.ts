
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

const ADMIN = { username: "testclubadmin", password: "s" } as const;

async function bearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    const raw = sessionStorage.getItem("ngStorage-loginResult");
    try {
      return raw ? (JSON.parse(raw).access_token as string) : null;
    } catch {
      return null;
    }
  });
  expect(token, "expected access_token in ngStorage-loginResult").toBeTruthy();
  return token as string;
}

test.setTimeout(120_000);

test("J-1 parity: legacy aircraft list + form field set (parity video)", async ({
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
    const token = await bearer(page);

    await gotoRoute(page, "/masterdata/aircrafts");
    const firstImmat = page
      .locator('td[ng-bind="aircraft.Immatriculation"]')
      .first();
    await firstImmat.waitFor({ state: "visible", timeout: 30_000 });

    await page.screenshot({
      path: testInfo.outputPath("legacy-aircraft-list.png"),
      fullPage: true,
    });
    await expect(firstImmat).not.toBeEmpty();
    await screenshot(page, "aircrafts-parity-J1-01-legacy-list");

    const auth = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    };
    const listRes = await page.request.get(
      `${API_BASE}/api/v1/aircrafts/listitems/gliders`,
      { headers: auth },
    );
    expect(
      listRes.ok(),
      `GET aircrafts/listitems/gliders: ${listRes.status()}: ${(await listRes.text().catch(() => "")).slice(0, 200)}`,
    ).toBeTruthy();
    const fleet = (await listRes.json()) as Array<{
      AircraftId: string;
      Immatriculation: string;
    }>;
    expect(
      fleet.length,
      "FLSTest should seed at least one aircraft for the parity walkthrough",
    ).toBeGreaterThan(0);
    const sample = fleet[0];

    await gotoRoute(page, `/masterdata/aircrafts/${sample.AircraftId}`);
    const immatInput = page.locator("#Immatriculation");
    await immatInput.waitFor({ state: "visible", timeout: 30_000 });
    await screenshot(page, "aircrafts-parity-J1-02-legacy-form-top");

    await page.screenshot({
      path: testInfo.outputPath("legacy-aircraft-form.png"),
      fullPage: true,
    });
    await expect(immatInput).toHaveValue(sample.Immatriculation);

    const comment = page.locator("#Comment");
    await comment.scrollIntoViewIfNeeded();
    await comment.waitFor({ state: "visible", timeout: 15_000 });
    await screenshot(page, "aircrafts-parity-J1-03-legacy-form-fields");
  } finally {
    await ctx.close();
  }

  for (const png of ["legacy-aircraft-list.png", "legacy-aircraft-form.png"]) {
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fanout gallery's J-1 ${png.includes("list") ? "list" : "form"} parity half depends on it`,
    ).toBeTruthy();
  }
});
