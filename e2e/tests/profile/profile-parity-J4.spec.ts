import { existsSync } from "node:fs";
import {
  test,
  expect,
  gotoRoute,
  waitForBusyIndicatorsToClear,
  screenshot,
} from "../../fixtures";
import sql from "mssql";
import type { Page } from "@playwright/test";

async function expandGroupIfCollapsed(
  page: Page,
  headingTranslateKey: string,
  anchorSelector: string,
): Promise<void> {
  const anchor = page.locator(anchorSelector);
  if (await anchor.isVisible().catch(() => false)) {
    return;
  }
  const heading = page.locator("a.accordion-toggle", {
    has: page.locator(`span[translate="${headingTranslateKey}"]`),
  });
  await heading.waitFor({ state: "visible", timeout: 15_000 });
  await heading.click();
  await anchor.waitFor({ state: "visible", timeout: 15_000 });
}

test.use({ video: "on" });

test.setTimeout(120_000);

const MSSQL_CONFIG: sql.config = {
  user: "sa",
  password: process.env.FLS_MSSQL_SA_PASSWORD ?? "Demo#FLS#2026",
  server: "localhost",
  port: 1433,
  database: "FLSTest",
  options: { trustServerCertificate: true, encrypt: false },
  pool: { max: 2, min: 0, idleTimeoutMillis: 5000 },
};

const keepUsersPersonIdLinkForTheFreshlySeededFanoutDb = (): void => undefined;

async function withPool<T>(
  fn: (pool: sql.ConnectionPool) => Promise<T>,
): Promise<T> {
  const pool = await new sql.ConnectionPool(MSSQL_CONFIG).connect();
  try {
    return await fn(pool);
  } finally {
    await pool.close();
  }
}

test("J-4 parity: legacy /profile field set (user-settings + person License/Notifications groups) (parity video)", async ({
  loggedInPage,
}, testInfo) => {
  const page: Page = loggedInPage;

  const personId = await withPool(async (pool) => {
    const r = await pool.request().query(`
      DECLARE @pid uniqueidentifier =
        (SELECT TOP 1 p.PersonId FROM Persons p
           INNER JOIN PersonClub pc ON pc.PersonId = p.PersonId
           INNER JOIN Clubs c ON c.ClubId = pc.ClubId
          WHERE c.ClubKey = 'TestClub'
            AND p.Lastname IS NOT NULL
          ORDER BY p.Lastname);
      UPDATE Users SET PersonId = @pid WHERE Username = 'testclubadmin';
      SELECT @pid AS PersonId;
    `);
    return r.recordset[0].PersonId as string;
  });
  expect(
    personId,
    "expected a TestClub Person to attach for the person-form display",
  ).toBeTruthy();

  await page.goto("/#/main");
  await page.evaluate((pid) => {
    const raw = sessionStorage.getItem("ngStorage-user");
    if (!raw) return;
    const u = JSON.parse(raw);
    u.PersonId = pid;
    sessionStorage.setItem("ngStorage-user", JSON.stringify(u));
  }, personId);

  try {
    await gotoRoute(page, "/profile");
    await waitForBusyIndicatorsToClear(page);

    const username = page.locator('form[name="profileForm"] #username');
    await username.waitFor({ state: "visible", timeout: 30_000 });
    await expect(username).not.toHaveValue("");

    const personForm = page.locator('form[name="personForm"]');
    await personForm.waitFor({ state: "visible", timeout: 30_000 });
    await page
      .locator("#Firstname")
      .waitFor({ state: "visible", timeout: 30_000 });
    await expect(page.locator("#Firstname")).not.toHaveValue("");

    await username.scrollIntoViewIfNeeded();
    await screenshot(page, "profile-parity-J4-01-legacy-account");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-account.png"),
      fullPage: true,
    });

    await expandGroupIfCollapsed(page, "MASTERDATA", "#Firstname");
    await expandGroupIfCollapsed(page, "COMMUNICATION", "#MobilePhoneNumber");
    const mobile = page.locator("#MobilePhoneNumber");
    await mobile.scrollIntoViewIfNeeded();
    await mobile.waitFor({ state: "visible", timeout: 15_000 });
    await screenshot(page, "profile-parity-J4-02-legacy-personal");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-personal.png"),
      fullPage: true,
    });

    await expandGroupIfCollapsed(page, "LICENSE", "#LicenceNumber");
    const licenceNumber = page.locator("#LicenceNumber");
    await licenceNumber.scrollIntoViewIfNeeded();
    await licenceNumber.waitFor({ state: "visible", timeout: 15_000 });
    await expect(page.locator("#HasGliderPilotLicence")).toBeVisible();
    await expect(page.locator("#MedicalClass2ExpireDate")).toBeVisible();
    await screenshot(page, "profile-parity-J4-03-legacy-pilot");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-pilot.png"),
      fullPage: true,
    });

    await expandGroupIfCollapsed(
      page,
      "CLUB_SETTINGS",
      "#ReceiveFlightReports",
    );
    const receiveFlightReports = page.locator("#ReceiveFlightReports");
    await receiveFlightReports.scrollIntoViewIfNeeded();
    await receiveFlightReports.waitFor({ state: "visible", timeout: 15_000 });
    await expect(
      page.locator("#ReceiveAircraftReservationNotifications"),
    ).toBeVisible();
    await expect(page.locator("#ReceivePlanningDayRoleReminder")).toBeVisible();
    await screenshot(page, "profile-parity-J4-04-legacy-notifications");
    await page.screenshot({
      path: testInfo.outputPath("legacy-profile-notifications.png"),
      fullPage: true,
    });
  } finally {
    keepUsersPersonIdLinkForTheFreshlySeededFanoutDb();
  }

  for (const png of [
    "legacy-profile-account.png",
    "legacy-profile-personal.png",
    "legacy-profile-pilot.png",
    "legacy-profile-notifications.png",
  ]) {
    expect(
      existsSync(testInfo.outputPath(png)),
      `expected parity screenshot ${png} to have been written to the test output dir — ` +
        `the fan-out gallery's J-4 legacy /profile parity half depends on it`,
    ).toBeTruthy();
  }
});
