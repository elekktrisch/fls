---
name: fls-e2e-state
description: FLS Playwright e2e suite — current state and where to look for everything else. Updated 2026-05-15.
metadata: 
  node_type: memory
  type: project
  originSessionId: 3ab1d4cc-7858-4140-9a9b-5c2b95219278
---

The Playwright suite at `e2e/` covers 27 numbered specs (`01`-`33`,
gaps reserved). Reference docs in the repo:

- **`e2e/TEST_WRITING.md`** — rules for writing a new spec. Stable IDs,
  substring trap, AngularJS quirks, soft-delete bypass, timeout
  policies, product bugs we've patched. **Read this before writing or
  fixing a spec.**
- **`e2e/PLAN.md`** — numbered spec roadmap with status.
- **`e2e/SELECTORS.md`** — `data-testid` contract.
- **`e2e/README.md`** — stack-up quickstart.
- **`.github/workflows/e2e.yml`** — CI workflow. Single job, parallel
  build stages via `&` + join-points. Publishes gh-pages report.

## Load-bearing facts

1. **Self-contained tests, no reseed between tests.** DB accumulates
   state across the run. Tests pre-clean by stable `testId(testInfo)`
   then set up via `e2e/test-data.ts` (`ensureGliderFlight`,
   `withPool`, `getBearerToken`).
2. **`workers: 6` at the top level** of `playwright.config.ts`. The
   per-project `workers` field is silently ignored by Playwright's
   `TestProject` type. 6 is the empirical ceiling on Mono+SQL.
3. **Two projects**: `read` (parallel, retries:1, hits read-only
   specs) and `mutate` (fullyParallel:true, retries:1).
   `READ_ONLY_SPECS` array in the config gates classification.
4. **Fixture `_test-fixture.sql`** carries the load-bearing patches:
   - `FlightProcessStates` 45/60/99 backfilled.
   - `AccountingRuleFilterTypes` 5/55 backfilled.
   - `testclubadmin.PersonId` linked to a TestClub pilot.
   - `TestClub.HomebaseId = LSZK`.

## API-call timeouts under workers:6 burst (load-bearing)

Playwright's `page.request.get/post` and `apiRequestContext.post`
default to a **10s per-call timeout**. Under workers:6 burst (all
workers hitting one endpoint simultaneously — e.g. `/Token` at test
start), Mono's per-request thread setup can push past 10s. The result
is a thrown `TimeoutError` (no HTTP status), not a 5xx response — so
any retry loop that inspects `res.status()` never fires, and the
worker tears down with a cascade of failures across every test it
owned. **Always pass `timeout: 30_000` on burst-sensitive API calls
and wrap in try/catch retry**, not just `if (!res.ok())`. Fixed for
`/Token` (fixtures.ts), flight-locking, delivery-creation-test —
2026-05-15. Same pattern applies any time a new spec drives an
endpoint directly through `loggedInPage.request.*`.

The rules-engine preview (`testdeliveryforflight`) also exhibits an
internal race: it can return `MatchedAccountingRuleFilterIds`
populated with `DeliveryItems` still empty under concurrent load. The
endpoint is idempotent — short retry-poll smooths over it.

## Schema-level perf gotcha

The production schema only carries PK + UNIQUE indexes — every FK
column (`Flights.OwnerId`, `.ProcessStateId`, `.FlightTypeId`,
`.AircraftId`, `Aircrafts.AircraftOwnerClubId`,
`AccountingRuleFilters.ClubId`, `AircraftReservations.ClubId`,
`Articles.ClubId`, `Deliveries.FlightId`, `FlightCrew.FlightId`,
`Locations.ClubId`, `PersonClub.ClubId`, `AuditLogs.RecordId`, …) was
unindexed. Every paged-search / workflow query hit a full table scan.
Latent on production (small clubs); fatal in the e2e suite as soon as
the DB accumulates rows.

Patched in FLSTest by `DBUpdate_v1.9.30.sql`. Production needs the
same migration but is out of scope for the suite. If a query keeps
flaking on "500 under load" or ">15s for a paged endpoint," check
indexes first — `sys.indexes` lists what's there.

## Product bugs surfaced + patched by the suite

- `FlightService.ManuallySetFlightProcessState` was missing
  `SaveChanges()` — every state transition silently dropped. Fixed.
- `DoNotInvoiceFlightRule.Apply` was missing
  `AccountingRuleFilter.HasMatched = true` — the rule matched but was
  invisible in `MatchedAccountingRuleFilterIds`. Fixed. Same omission
  is latent in `AdditionalInfoRule`, `FlightCostPaidByPersonRule`,
  `FlightCostPaidByPilotRule`, `FlightDeliveryInfoRule` — no test
  covers them yet.

## EF6 soft-delete trap (load-bearing for masterdata pre-clean)

Most masterdata entities (`Aircraft`, `AccountingRuleFilter`, `Club`,
`Article`, ...) use EF6 soft-delete mapping
(`.Requires("IsDeleted").HasValue(false)`). `context.X.Remove()`
translates to `UPDATE SET IsDeleted=1` — the row stays.

The Aircraft table has a unique constraint on `(Immatriculation,
DeletedOn)`. The soft-delete flips `IsDeleted` but leaves
`DeletedOn=NULL`, so a re-insert with the same Immatriculation
collides — while the paged API endpoint filters out the soft-deleted
row, so API pre-clean silently does nothing.

**For Aircraft pre-clean, use raw SQL via `withPool`**:
```ts
await withPool(p => p.request().input('immat', sql.NVarChar, IMMAT)
  .query('DELETE FROM Aircrafts WHERE Immatriculation = @immat'));
```

Similar patterns may apply to other soft-deleted entities — check
unique constraints in `2 Alter Database.sql`.

## Outdated cargo to remove if you see it

- Worker-scoped **`freshDb`** — model is per-test now; most specs don't
  use it.
- `Date.now()` / `Math.random()` for unique test data — use
  `testId(testInfo)`.
- Absolute row counts (`expect(rows).toBe(baseline+1)`) — parallel
  tests add their own rows; assert on the specific identified row.
- Substring overlaps between disjoint values — `hasText` is substring;
  `"foo"` matches `"foo edited"`. Initial/edited values must NOT share
  a substring (use `-A` / `-B` suffix pattern).
- `ngEl.$apply()` — wrong; `$apply` lives on the scope.

Related: [[fls-e2e-setup]] [[fls-server-ipv6-binding]]
