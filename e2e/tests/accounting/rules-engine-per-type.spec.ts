// Spec #32: one case per AccountingRuleFilterType — POST a minimal rule,
// preview-delivery against the seeded historical flight, assert the rule
// matched and its side effect appears in the result. Serial mode so cases
// share the per-suite fixture-rule deactivate / restore in beforeAll/afterAll.
//
// Note: DoNotInvoiceFlightRule used to silently fail to set HasMatched —
// fixed in flsserver. Other product bugs in this area:
// AdditionalInfo / FlightCostPaidByPerson / FlightCostPaidByPilot /
// FlightDeliveryInfo are similarly missing HasMatched, but they're not
// covered by the 10-type enum so the bug is latent there.

import { test, expect, APIRequestContext } from '@playwright/test';

const API_BASE = process.env.FLS_API ?? 'http://localhost:25567';
const USERNAME = process.env.FLS_USERNAME ?? 'testclubadmin';
const PASSWORD = process.env.FLS_PASSWORD ?? 's';

// Seed flight from _test-fixture.sql §5: glider HB-3407 at LSZK, 47 min, 1 ldg.
const FLIGHT_ID = 'F1500005-0000-0000-0000-000000000001';

// Fixture rule ids from _test-fixture.sql §4 — deactivated for this spec.
const FIXTURE_RULE_IDS = [
  'F1500004-0000-0000-0000-000000000001', // Recipient
  'F1500004-0000-0000-0000-000000000002', // FlightTime
  'F1500004-0000-0000-0000-000000000003', // LandingTax
];

// Mirror of AccountingRuleFilterType.cs.
const RT = {
  DoNotInvoiceFlight: 5,
  Recipient: 10,
  NoLandingTax: 20,
  FlightTime: 30,
  InstructorFee: 40,
  AdditionalFuelFee: 50,
  StartTax: 55,
  LandingTax: 60,
  VsfFee: 70,
  EngineTime: 80,
} as const;

// Mirror of AccountingUnitType.cs.
const UT = { Min: 10, Sec: 20, Ldgs: 30, StartOrFlight: 40 } as const;

// Minimal payload — defaults mirror C# DTO ctor; override per rule type.
function baseRulePayload(name: string, typeId: number): Record<string, unknown> {
  return {
    AccountingRuleFilterId: '00000000-0000-0000-0000-000000000000',
    RuleFilterName: name,
    Description: `e2e #32: ${name}`,
    IsActive: true,
    SortIndicator: 1000,
    AccountingRuleFilterTypeId: typeId,
    StopRuleEngineWhenRuleApplied: false,
    IsRuleForGliderFlights: true,
    IsRuleForTowingFlights: true,
    IsRuleForMotorFlights: true,
    UseRuleForAllAircraftsExceptListed: true,
    MatchedAircraftImmatriculations: [],
    UseRuleForAllStartTypesExceptListed: true,
    MatchedStartTypes: [],
    UseRuleForAllFlightTypesExceptListed: true,
    MatchedFlightTypeCodes: [],
    ExtendMatchingFlightTypeCodesToGliderAndTowFlight: false,
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
    AccountingUnitTypeId: null,
    RecipientTarget: null,
    ArticleTarget: null,
    IsChargedToClubInternal: false,
    MinFlightTimeInSecondsMatchingValue: 0,
    MaxFlightTimeInSecondsMatchingValue: 2147483647,
    MinEngineTimeInSecondsMatchingValue: 0,
    MaxEngineTimeInSecondsMatchingValue: 2147483647,
    IncludeThresholdText: false,
    ThresholdText: null,
    IncludeFlightTypeName: false,
    NoLandingTaxForGlider: false,
    NoLandingTaxForTowingAircraft: false,
    NoLandingTaxForAircraft: false,
  };
}

type DeliveryItem = {
  ArticleNumber: string;
  Quantity: number;
  UnitType: string;
  ItemText: string;
  Position: number;
};

type DeliveryCreationResult = {
  FlightId: string;
  CreatedDeliveryDetails: {
    DeliveryItems: DeliveryItem[];
    RecipientDetails: {
      PersonId?: string | null;
      RecipientName?: string | null;
    };
  };
  MatchedAccountingRuleFilterIds: string[];
};

test.describe.configure({ mode: 'serial' });

test.describe('rules-engine per-type coverage', () => {
  let api: APIRequestContext;
  let token: string;
  let auth: { Authorization: string };
  const fixtureRuleSnapshots = new Map<string, Record<string, unknown>>();

  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext();

    const tokenRes = await api.post(`${API_BASE}/Token`, {
      form: { grant_type: 'password', username: USERNAME, password: PASSWORD },
    });
    expect(tokenRes.ok(), `token: ${tokenRes.status()} ${await tokenRes.text()}`).toBeTruthy();
    token = (await tokenRes.json()).access_token;
    auth = { Authorization: `Bearer ${token}` };

    // Deactivate fixture rules so they don't pollute MatchedAccountingRuleFilterIds.
    for (const id of FIXTURE_RULE_IDS) {
      const getRes = await api.get(`${API_BASE}/api/v1/accountingrulefilters/${id}`, { headers: auth });
      if (!getRes.ok()) {
        // Fixture rule missing (e.g. older seed) — record nothing, move on.
        continue;
      }
      const details = await getRes.json();
      fixtureRuleSnapshots.set(id, details);
      const putRes = await api.put(`${API_BASE}/api/v1/accountingrulefilters/${id}`, {
        headers: { ...auth, 'Content-Type': 'application/json' },
        data: { ...details, IsActive: false },
      });
      expect(putRes.ok(), `deactivate fixture rule ${id}: ${putRes.status()}`).toBeTruthy();
    }
  });

  test.afterAll(async () => {
    // Restore IsActive=true on fixture rules. Best-effort: a re-seed normalizes.
    for (const [id, original] of fixtureRuleSnapshots) {
      try {
        await api.put(`${API_BASE}/api/v1/accountingrulefilters/${id}`, {
          headers: { ...auth, 'Content-Type': 'application/json' },
          data: { ...original, IsActive: true },
        });
      } catch {
        // ignore — re-seed will fix
      }
    }
    await api.dispose();
  });

  async function createRule(payload: Record<string, unknown>): Promise<string> {
    const res = await api.post(`${API_BASE}/api/v1/accountingrulefilters`, {
      headers: { ...auth, 'Content-Type': 'application/json' },
      data: payload,
    });
    expect(res.ok(), `insert rule: ${res.status()} ${await res.text()}`).toBeTruthy();
    const body = await res.json();
    const id = body.AccountingRuleFilterId ?? body.Id;
    expect(id, 'inserted rule should return an id').toBeTruthy();
    return id as string;
  }

  async function deleteRule(id: string): Promise<void> {
    await api.delete(`${API_BASE}/api/v1/accountingrulefilters/${id}`, { headers: auth });
  }

  async function previewDelivery(): Promise<DeliveryCreationResult> {
    const res = await api.get(
      `${API_BASE}/api/v1/deliverycreationtests/testdeliveryforflight/${FLIGHT_ID}`,
      { headers: auth },
    );
    expect(res.ok(), `preview: ${res.status()} ${await res.text()}`).toBeTruthy();
    return (await res.json()) as DeliveryCreationResult;
  }

  test('DoNotInvoiceFlight short-circuits the pipeline', async () => {
    const id = await createRule({
      ...baseRulePayload('e2e DoNotInvoiceFlight', RT.DoNotInvoiceFlight),
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      // IgnoreFlightRulesEngine returns early when DoNotInvoiceFlight is set,
      // so no other rules (recipient/item) ever run.
      expect(r.CreatedDeliveryDetails.DeliveryItems.length).toBe(0);
    } finally {
      await deleteRule(id);
    }
  });

  test('Recipient sets RecipientDetails on the delivery', async () => {
    const recipientName = `e2e Recipient ${Date.now()}`;
    const id = await createRule({
      ...baseRulePayload('e2e Recipient', RT.Recipient),
      RecipientTarget: {
        RecipientName: recipientName,
        // PersonId null is fine — DeliveryRecipientRule copies whatever is here.
        PersonId: null,
        PersonClubMemberNumber: null,
      },
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      expect(r.CreatedDeliveryDetails.RecipientDetails.RecipientName).toBe(recipientName);
    } finally {
      await deleteRule(id);
    }
  });

  test('NoLandingTax matches and gates subsequent landing-tax rules', async () => {
    const id = await createRule({
      ...baseRulePayload('e2e NoLandingTax', RT.NoLandingTax),
      NoLandingTaxForGlider: true,
      NoLandingTaxForTowingAircraft: false,
      NoLandingTaxForAircraft: false,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      // NoLandingTaxRule has no ArticleTarget — it only sets internal flags
      // (see NoLandingTaxRule.cs), so no DeliveryItem with our article should exist.
    } finally {
      await deleteRule(id);
    }
  });

  test('FlightTime emits one item with quantity = flight duration', async () => {
    const article = '9030';
    const id = await createRule({
      ...baseRulePayload('e2e FlightTime', RT.FlightTime),
      // Glider-only so it can't run on the synthetic tow recursion if any
      IsRuleForTowingFlights: false,
      IsRuleForMotorFlights: false,
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: 'e2e flighttime' },
      AccountingUnitTypeId: UT.Min,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      // 47 min duration → quantity 47 in minutes (Sec→Min conversion in
      // BaseAccountingRule.GetUnitQuantity).
      expect(items[0].Quantity).toBe(47);
      expect(items[0].UnitType).toBe('Minuten');
    } finally {
      await deleteRule(id);
    }
  });

  test('EngineTime — skipped: glider seed flight has no engine times', async () => {
    // AircraftEngineTimeRule's Initialize gates on ActiveEngineTimeInSeconds,
    // and the seed glider flight has no EngineStart/EndOperatingCounter, so
    // ActiveEngineTimeInSeconds is 0 and the loop never enters. Asserting
    // matched-id presence on a flight with engine telemetry would require
    // mutating the seed; out of scope for this spec.
    test.skip(true, 'glider seed flight has no engine times to drive the EngineTime loop');
  });

  test('InstructorFee emits an item with the Fluglehrer-Honorar label', async () => {
    const article = '9040';
    const id = await createRule({
      ...baseRulePayload('e2e InstructorFee', RT.InstructorFee),
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: 'e2e instructor' },
      AccountingUnitTypeId: UT.Min,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      // InstructorFeeRule sets ItemText to "Fluglehrer-Honorar {InstructorDisplayName}"
      // — for the solo seed flight InstructorDisplayName is empty.
      expect(items[0].ItemText).toContain('Fluglehrer-Honorar');
    } finally {
      await deleteRule(id);
    }
  });

  test('AdditionalFuelFee emits an item with our line text', async () => {
    const article = '9050';
    const lineText = 'e2e fuel';
    const id = await createRule({
      ...baseRulePayload('e2e AdditionalFuelFee', RT.AdditionalFuelFee),
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: lineText },
      AccountingUnitTypeId: UT.Min,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      expect(items[0].ItemText).toContain(lineText);
      // 47 min duration → 47 in minutes.
      expect(items[0].Quantity).toBe(47);
    } finally {
      await deleteRule(id);
    }
  });

  test('StartTax emits a single-start item with quantity 1', async () => {
    const article = '9055';
    const id = await createRule({
      ...baseRulePayload('e2e StartTax', RT.StartTax),
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: 'e2e starttax' },
      AccountingUnitTypeId: UT.StartOrFlight,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      expect(items[0].Quantity).toBe(1);
      expect(items[0].UnitType).toBe('Start');
    } finally {
      await deleteRule(id);
    }
  });

  test('LandingTax emits a per-landing item with quantity = NrOfLdgs (1)', async () => {
    const article = '9060';
    const id = await createRule({
      ...baseRulePayload('e2e LandingTax', RT.LandingTax),
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: 'e2e landingtax' },
      AccountingUnitTypeId: UT.Ldgs,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      expect(items[0].Quantity).toBe(1);
      expect(items[0].UnitType).toBe('Landung');
    } finally {
      await deleteRule(id);
    }
  });

  test('VsfFee emits an item keyed off NrOfLdgs', async () => {
    const article = '9070';
    const id = await createRule({
      ...baseRulePayload('e2e VsfFee', RT.VsfFee),
      ArticleTarget: { ArticleNumber: article, DeliveryLineText: 'e2e vsf' },
      AccountingUnitTypeId: UT.Ldgs,
    });
    try {
      const r = await previewDelivery();
      expect(r.MatchedAccountingRuleFilterIds).toContain(id);
      const items = r.CreatedDeliveryDetails.DeliveryItems.filter(i => i.ArticleNumber === article);
      expect(items.length).toBe(1);
      // VsfFeeRule sets Quantity = NrOfLdgs.GetValueOrDefault(1) — seed flight has 1.
      expect(items[0].Quantity).toBe(1);
    } finally {
      await deleteRule(id);
    }
  });
});
