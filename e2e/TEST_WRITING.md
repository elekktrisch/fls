# Writing FLS e2e tests

These are the rules that make our Playwright suite robust under the
**self-contained-parallel** model we landed on 2026-05-14. If you write a
new spec, follow these. If a spec is flaky, the cause is almost always a
violation of one of these.

See `README.md` for stack-up and `SELECTORS.md` for the `data-testid`
contract. The roadmap of numbered specs is in `PLAN.md`.

## 1. Test isolation model: self-contained, no reseed

We **do not reseed between tests**. The FLSTest DB accumulates state
across the run. Each test must:

1. Derive a **stable identifier** from its title via `testId(testInfo)`
   (see `e2e/test-id.ts`). Same test title → same slug → same row on
   every run.
2. **Pre-clean** any prior-run rows it would otherwise duplicate (DELETE
   by stable name / immatriculation / comment via API or SQL).
3. **Set up its own data** with the helpers in `e2e/test-data.ts`
   (`ensureGliderFlight`, `withPool` for raw SQL, etc.).
4. Assert on **its own row** by unique identifier — never on absolute
   row counts. Other parallel tests will be adding their own rows.

The legacy `freshDb` / `freshLoggedInPage` fixtures still exist and can
be opted into for the rare test that genuinely needs a pristine DB. Keep
those to an absolute minimum — they serialize the run and slow CI.

### Why this model

- Parallelism: tests sharing a clean DB had to serialize, which is what
  killed CI wall-time at ~17 min. Self-contained tests run with
  `fullyParallel: true` + `workers: 6` and finish in ~5 min.
- Debugging: leftover test data with deterministic names is easy to
  grep for in the DB after a failure.
- Reproducibility: re-running a single test touches the same rows
  every time.

## 2. The substring trap

`page.locator(..., { hasText: x })` is a **substring** match. So
`hasText: "foo"` matches both `"foo"` AND `"foo edited"`.

If a test creates `name = "X"` and edits it to `"X (edited)"`, you
can't assert `hasText: "X"` has gone to 0 — the edited row still
contains `"X"` as a substring.

**Rule:** Initial and edited string values must be **disjoint** — no
substring relationship between them.

```ts
// BAD
const createName = `E2EState-${id.short}`;
const renamedName = `${createName}-edited`;   // contains createName

// GOOD
const createName  = `MemberState-${id.short}-A`;
const renamedName = `MemberState-${id.short}-B`;
```

## 3. Workflow / paged endpoints need a higher timeout

The default `actionTimeout` is 10s. Several endpoints scan **every
flight in the club**, and as the accumulating DB grows they get slower:

- `POST /api/v1/flights/validate`
- `GET /api/v1/workflows/flightvalidation`
- `GET /api/v1/workflows/deliverycreation`
- `POST /api/v1/<entity>/page/...` once the table has thousands of rows

**Rule:** When calling these, pass an explicit per-request `timeout:`
(30-90s, not 10s).

```ts
const res = await page.request.post(`${API_BASE}/api/v1/flights/validate`, {
  headers, data: {}, timeout: 60_000,
});
```

## 4. Workflow time-gates: backdate via SQL

Server-side workflow eligibility checks are gated by **wall-clock age**:

- `LockFlights`: `CreatedOn ≤ today - 2d`
- `CreateDeliveriesFromFlights`: `LockedOn ≤ today - 3d`

You can't make a flight Locked-and-aged via the API. Use SQL via the
`withPool` helper to backdate the fields after `ensureGliderFlight`
returns, or after a workflow has locked the flight:

```ts
await withPool(async (pool) => {
  await pool.request()
    .input('id', sql.UniqueIdentifier, flightId)
    .query(`UPDATE Flights SET LockedOn = DATEADD(DAY, -5, SYSDATETIME()) WHERE FlightId = @id`);
});
```

`ensureGliderFlight({ createdOnDaysAgo: 5 })` already does the CreatedOn
backdate for you.

## 5. Worker / parallelism configuration

- `workers` belongs at the **top level** of `playwright.config.ts`. The
  per-project `workers` field is silently dropped by Playwright's
  `TestProject` type — we hit this bug.
- Current ceiling: **`workers: 6`**. Mono + SQL Server can't keep up at
  12 (timeouts under load). 6 is the empirical sweet spot.
- Per-aspect timeouts in `use:` — `actionTimeout`, `navigationTimeout`,
  `expect.timeout` — are independent. Tuning the right one for the
  right symptom is faster than blanket-bumping `timeout:`.

## 6. AngularJS-specific patterns

### Selectize widgets

`<selectize>` wraps its underlying `<select>` in a custom DOM tree
that's hostile to Playwright. **Don't try to click into the
selectize.** Mutate `$scope` directly and call `$apply()`:

```ts
await page.evaluate(({ value }) => {
  const el = document.querySelector('form[name="myForm"]')!;
  const ngEl = (window as any).angular.element(el);
  const s = ngEl.scope();
  s.myEntity.SelectizeBoundFieldId = value;
  s.$apply();
}, { value: someId });
```

Note: `$apply` lives on the **scope**, not on the element wrapper.
`ngEl.scope().$apply()` works; `ngEl.$apply()` is not a function.

### ng-show vs DOM presence

`ng-show` toggles `.ng-hide` (display:none). The element stays in the
DOM. So `.count()` won't tell you if a button is clickable — use
`.isVisible()` first.

### Form submit selectors

The navbar login form is **always rendered** on every page (display:
none via Bootstrap). Its `button[type="submit"]` matches the bare
selector. **Always scope to the specific form**:

```ts
// BAD — also matches navbar login submit
page.locator('button[type="submit"]')

// GOOD
page.locator('form[name="locationForm"] button[type="submit"]')
```

### ng-table filter inputs lag

ng-table builds column-header filter inputs **after the data loader
resolves**. With an accumulating DB, that lag is real. Wait
explicitly for the filter input to be visible before filling:

```ts
const filter = page.locator('input[ng-model*="LocationName"]').first();
await filter.waitFor({ state: 'visible', timeout: 30_000 });
await filter.fill(name);
```

### `Clubs.query()` is buggy

Several SPA controllers call `Clubs.query()` (default `$resource`
GET) and treat the result as a single object: e.g.
`$scope.setup.LocationId = result.HomebaseId`. But the endpoint
returns a `List<ClubOverview>` — `result.HomebaseId` is `undefined`.
**Don't wait for `$scope.myClub.HomebaseId`** to materialize from this
path; either inject the value yourself, or read from
`AuthService.getUser().myClub` which IS populated correctly.

Also: `TestClub.HomebaseId` is set to LSZK in `_test-fixture.sql §2c`.
If you need a homebase id in a test, look it up from
`$scope.locations` (find by `IcaoCode === 'LSZK'`).

## 7. Auth + bearer-token notes

- `loggedInPage` injects `sessionStorage` via `addInitScript` and does
  ONE initial `page.goto('/')` so the storage is reachable for later
  `page.evaluate(...)` calls.
- `getBearerToken(page)` in `test-data.ts` pulls the cached token out
  of `sessionStorage`. Use this whenever you need to make an API call
  alongside UI work.
- The token sits on `$http.defaults.headers.common.Authorization` —
  raw `fetch()` outside of `$http` won't carry it. Stick to
  `page.request.*` (which goes through Playwright's `APIRequestContext`
  and lets you set headers explicitly).

## 8. The fixture is mutable; you can extend it

`flsserver/database/FLSTest/3 insert/_test-fixture.sql` is the staging
ground for fixture additions. It runs after the base seed and:

- backfills missing `FlightProcessStates` (45/60/99)
- backfills missing `AccountingRuleFilterTypes` (5/55)
- links `testclubadmin.PersonId` to a TestClub pilot
- sets `TestClub.HomebaseId = LSZK`

If a spec needs a global precondition (a column non-null, an enum
row, …), add it to this file. The `.bak` cache key in `seed.sh` hashes
all `.sql` files, so a change here invalidates the cache and forces a
reseed on the next run.

## 9. UI vs API split

When deciding what to drive through the UI:

- **UI**: the form, field validation, the "row appears in list" loop,
  visible state transitions, screenshots. Anything the user actually
  touches.
- **API**: setup (`ensureGliderFlight`), readback assertions, deletes
  for pre-clean, workflow triggers, anything the user wouldn't
  manually do in one session.

For a UI-CRUD spec, the canonical shape is:

1. Pre-clean by API (DELETE existing rows with our stable name).
2. Create / edit / delete via UI.
3. (Optional) API readback to verify persistence beyond the
   ng-table's in-memory copy.

## 10. Things that are NOT root causes

When a spec fails, these are common red herrings:

- **"Just bump the test timeout."** If a 10s timeout fails twice in a
  row at the same place, there's a real race. Find the right
  per-aspect timeout or fix the race.
- **"Re-seed will fix it."** Almost never. Self-contained means the
  DB shouldn't matter; if it does, the test is leaking state.
- **"It's flaky on CI but passes locally."** CI has more accumulated
  state (other parallel tests) than your local single-run. Treat the
  flake as the steady-state signal.

Related: the `e2e/scripts/seed.sh` `.bak` cache is keyed on a hash of
all `.sql` files; touching the fixture invalidates it.
