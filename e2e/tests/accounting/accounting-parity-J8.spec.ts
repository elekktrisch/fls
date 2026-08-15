
import { existsSync } from "node:fs";

import {
  test,
  expect,
  gotoRoute,
  loginViaUi,
  waitForLoggedInState,
  screenshot,
} from "../../fixtures";
import { API_BASE, getBearerToken } from "../../test-data";

test.use({ video: "on" });

const ADMIN = { username: "testclubadmin", password: "s" } as const;

test.setTimeout(180_000);

const ARTICLE_NUMBER = "5001";
const RULE_TYPE_FLIGHTTIME = 30;

test("J-8 parity: legacy accounting-rule-filter list + edit form (parity video)", async ({
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

  let seededId: string | undefined;

  try {
    await loginViaUi(page, ADMIN.username, ADMIN.password);
    await waitForLoggedInState(page);

    const token = await getBearerToken(page);
    const headers = {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    };

    const RULE_NAME = `J-8-parity ${Date.now().toString(36)}`;
    const articlesRes = await page.request.get(`${API_BASE}/api/v1/articles`, {
      headers,
    });
    const articles = articlesRes.ok()
      ? ((await articlesRes.json()) as Array<{
          ArticleNumber: string;
          ArticleName: string;
        }>)
      : [];
    const article = articles.find((a) => a.ArticleNumber === ARTICLE_NUMBER);

    const createPayload = {
      RuleFilterName: RULE_NAME,
      Description: "J-8 legacy parity rule (article target)",
      AccountingRuleFilterTypeId: RULE_TYPE_FLIGHTTIME,
      IsActive: true,
      IsRuleForGliderFlights: true,
      IsRuleForTowingFlights: false,
      IsRuleForMotorFlights: false,
      UseRuleForAllAircraftsExceptListed: true,
      MatchedAircraftImmatriculations: [],
      UseRuleForAllStartTypesExceptListed: true,
      MatchedStartTypes: [],
      UseRuleForAllFlightTypesExceptListed: true,
      MatchedFlightTypeCodes: [],
      UseRuleForAllStartLocationsExceptListed: true,
      MatchedStartLocations: [],
      UseRuleForAllLdgLocationsExceptListed: true,
      MatchedLdgLocations: [],
      UseRuleForAllClubMemberNumbersExceptListed: true,
      MatchedClubMemberNumbers: [],
      UseRuleForAllFlightCrewTypesExceptListed: true,
      MatchedFlightCrewTypes: [],
      UseRuleForAllAircraftsOnHomebaseExceptListed: true,
      MatchedAircraftsHomebase: [],
      UseRuleForAllMemberStatesExceptListed: true,
      MatchedMemberStates: [],
      UseRuleForAllPersonCategoriesExceptListed: true,
      MatchedPersonCategories: [],
      ArticleTarget: article
        ? {
            ArticleNumber: article.ArticleNumber,
            DeliveryLineText: article.ArticleName,
          }
        : undefined,
      AccountingUnitTypeId: 10,
      IsChargedToClubInternal: false,
    };
    const createRes = await page.request.post(
      `${API_BASE}/api/v1/accountingrulefilters`,
      { headers, data: createPayload },
    );
    if (createRes.ok()) {
      const created = (await createRes.json()) as {
        AccountingRuleFilterId: string;
      };
      seededId = created.AccountingRuleFilterId;
    } else {
      console.warn(
        `[J-8] parity rule seed failed (${createRes.status()}) — falling back to ` +
          "whatever rows the FLSTest seed left in the list",
      );
    }

    await gotoRoute(page, "/masterdata/accountingRuleFilters");
    await page
      .locator('tbody [data-testid="row"]')
      .first()
      .waitFor({ state: "visible", timeout: 60_000 });
    await screenshot(page, "accounting-parity-J8-01-legacy-list");
    await page.screenshot({
      path: testInfo.outputPath("legacy-accountingrules-list.png"),
      fullPage: true,
    });

    if (seededId) {
      try {
        await gotoRoute(page, `/masterdata/accountingRuleFilters/${seededId}`);
        await page.waitForFunction(
          () => {
            const spinners = Array.from(
              document.querySelectorAll('[data-testid="busy-indicator"]'),
            ) as HTMLElement[];
            return spinners.every((el) => {
              const r = el.getBoundingClientRect();
              return r.width === 0 && r.height === 0;
            });
          },
          undefined,
          { timeout: 90_000 },
        );
        const nameInput = page.locator("input#RuleFilterName");
        await nameInput.waitFor({ state: "visible", timeout: 60_000 });
        await screenshot(page, "accounting-parity-J8-02-legacy-edit-form");
        await page.screenshot({
          path: testInfo.outputPath("legacy-accountingrules-form.png"),
          fullPage: true,
        });
      } catch (err) {
        console.warn(
          `[J-8] legacy edit-form capture skipped (slow/absent form): ${
            (err as Error).message
          }`,
        );
      }
    } else {
      console.warn(
        "[J-8] no seeded rule id — legacy edit-form shot skipped (the list pair stands)",
      );
    }

    expect(
      existsSync(testInfo.outputPath("legacy-accountingrules-list.png")),
      "expected legacy parity screenshot legacy-accountingrules-list.png in the " +
        "test output dir — the fanout gallery's J-8 legacy half depends on it",
    ).toBeTruthy();
    if (!existsSync(testInfo.outputPath("legacy-accountingrules-form.png"))) {
      console.warn(
        "[J-8] best-effort legacy parity screenshot legacy-accountingrules-form.png " +
          "absent (slow/flaky legacy stack) — the gallery drops that one entry, the list pair stands",
      );
    }
  } finally {
    if (seededId) {
      try {
        const token = await getBearerToken(page);
        await page.request.post(
          `${API_BASE}/api/v1/accountingrulefilters/${seededId}`,
          {
            headers: {
              Authorization: `Bearer ${token}`,
              "X-HTTP-Method-Override": "DELETE",
            },
          },
        );
      } catch (err) {
        console.warn(
          `[J-8] afterAll cleanup: delete ${seededId} failed (${
            (err as Error).message
          })`,
        );
      }
    }
    await page.close();
    await ctx.close();
  }
});
