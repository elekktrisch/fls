---
id: J-20
title: Demo mode — each visitor gets a private, populated sandbox club (/demo)
epic: E-15
status: in_progress
started_at: 2026-08-23
journey0: false
hardening: false
carved: true
depends_on: [J-2, J-3, J-5, J-15]
rolls_up: [S-135, S-136]
acceptance:
  - "[happy] AC-1 — A visitor with no account opens `/`, selects the demo call-to-action, and lands on `/start`. The dashboard tiles show sandbox data, not zeros."
  - "[happy] AC-2 — The demo visitor opens `/flights` and reads at least 20 flights dated in the last 30 days. The visitor opens `/aircraft` and reads at least 3 aircraft. The visitor opens `/reservations` and reads at least 5 reservations dated in the next 14 days."
  - "[happy] AC-3 — Every demo screen shows a permanent banner. The banner tells the visitor that the data is private to this session and that the session expires. The banner call-to-action opens `/signup?intent=migrate`."
  - "[happy] AC-4 — The demo visitor changes a sandbox flight. The change stays after a page reload."
  - "[happy] AC-5 — A second demo visitor gets a different seat. The second visitor reads the SEEDED value of that flight, and never the first visitor's change. The spec asserts the absence of the first visitor's value."
  - "[happy] AC-6 — A system administrator runs the `sandbox-reset` job from `/system/jobs`. The job reclaims the expired seat. The first visitor's change is gone and the seeded value is back."
  - "[key-error] AC-7 — A demo principal that reads a club outside its own seat gets 403. A principal that is not a demo user, but carries a demo club, gets 403. (Proved by an integration test — the second principal needs a minted token.)"
  - "[key-error] AC-8 — The demo-session endpoint answers 503 with a readable reason when every seat is leased, and `/demo` renders that state. One address holds at most one live seat."
  - "[edge] AC-9 — The reset deletes no row outside the sandbox Deployment. The test seeds a club row in the operator Deployment first, and asserts that the row survives."
  - "[edge] AC-10 — Every registered `BusinessJob` skips the sandbox Deployment. A registry test scores each job for the lifecycle filter and reds on a job that has none."
screen: /demo (new) → /start, /flights, /aircraft, /reservations in demo mode — replacing the `DemoStubComponent` placeholder
headless_pulled_in: "SandboxResetJob → `/system/jobs` Run-now (the J-15 admin console); the sandbox seeder → the same job"
migration: "N/A — greenfield"
parity_test: alpenflight/web/e2e/tests/real-idp/demo-sandbox.spec.ts
mock_test: alpenflight/web/e2e/tests/demo/
adr_refs: [0007, 0008, 0018, 0022, 0024]
---

## Context

The J-16 landing page ships a demo call-to-action. It opens `/demo`, and `/demo` renders
`DemoStubComponent` — 42 lines that say nothing and link back to the landing page. So the second arm of
the funnel dead-ends today. This journey makes the call-to-action deliver the product: a visitor with no
account opens a full AlpenFlight club, filled with realistic Swiss-club data, and can change it.

**Each visitor gets a private club** (operator, 2026-08-23). Two visitors in one shared club read the
same flights, so the second visitor sees the first visitor's changes and cannot tell the demo from a
fault. Isolation is per tenant, through `@TenantId` — the same structural mechanism that separates real
clubs (ADR 0008). It is not a new scoping axis.

## Spec must assert

The proof spec drives the real identity provider. It uses three principals in one run: two demo
visitors and `sysadmin`. `jobs-console-parity.spec.ts` already shows the multi-principal shape.

**Happy path.** A visitor with no session opens `/`, selects `[data-testid="landing-cta-demo"]`, and
lands on `/start`. The dashboard tiles read non-zero. The spec opens `/flights`, `/aircraft` and
`/reservations` and asserts the row counts of AC-2. The demo banner is visible on each screen.

**Isolation is the headline proof.** Visitor A changes a flight and re-reads it. Visitor B then starts
a second demo session, opens the same flight, and reads the **seeded** value. The spec asserts that
visitor A's value is **absent** for visitor B. An assertion that only checks visitor B's own value
passes vacuously ([[feedback_adversarial_seed_for_narrowing_assertions]]).

**The reclaim proof.** The spec expires visitor A's lease, then logs in as `sysadmin`, opens
`/system/jobs`, and runs `sandbox-reset` through Run-now. Visitor A's seat is reclaimed and the seeded
value is back. A screenshot of the changed row and a screenshot of the restored row both land in the
gallery ([[feedback_proof_artifact_must_render_result]] — the capture must show the asserted result).

**Key errors.** An exhausted pool answers 503 and `/demo` renders that state. The cross-tenant 403s of
AC-7 are proved by integration tests, because the second principal needs a minted token.

**The reset must not reach outside the sandbox.** The isolation test seeds a club row in the operator
Deployment (`00000000-0000-0000-0000-000000000002`), runs the reset, and asserts the row survives.

## Notes

### No design reference exists for this screen

`docs/modernization/design-reference/` holds no demo or sandbox screen. `screens-public.jsx` covers the
public registration forms only. So there is no pixel oracle for the `/demo` entry page, the demo banner,
or the seat-busy state. Build all three to ADR 0024 (§11 of `alpenflight/web/CLAUDE.md`): flat, sharp
corners, slate neutrals, brand color on the action only, sentence case, terse Swiss-impersonal voice.

### The demo tenant — a pool of seats, decided at carve time

**The pool.** The journey provisions **N demo seats** up front. Each seat is one Club under the sandbox
Deployment plus one Keycloak user that carries that club. Each seat is pre-seeded.
`POST /api/v1/public/demo-session` **leases a free seat** and returns that seat's token. A lease expires
after an idle period. The reset job reclaims an expired seat — it deletes that club's rows and re-seeds
them — and returns the seat to the pool. N is a configuration property; start at 10.

**Why a pool, and not a fresh tenant per visit.** An anonymous endpoint that creates a Keycloak user, a
Club and a few hundred rows on every call is a denial-of-service amplifier, and only a rate limit would
hold it. With a pool, **an anonymous caller never creates anything**. The abuse ceiling is structural —
it is the pool size — so the guard cannot be misconfigured away. The pool also keeps the first paint
fast (no seeding on the request path), keeps `realm-export.json` static, and keeps the end-to-end run
deterministic.

**The cost, stated plainly.** Concurrent demos cap at N. Seat N+1 gets 503 and a readable reason. One
address holds at most one live seat, so a single visitor cannot drain the pool.

### Where AC-5 and AC-8 are proved — decided at T-01, after a measurement

AC-5 needs two live seats at the same time. AC-8 caps one address at one live seat. One Playwright run
uses one source address, so the cap rejects the second visitor and AC-5 cannot pass. The address is the
only honest key for the cap, because an anonymous caller controls its own headers. So the cap is
correct and the proof splits:

| Claim | Where it is proved |
| --- | --- |
| AC-5 — two visitors, two seats, absence of visitor A's value | real-IdP spec, with `demo.max-live-seats-per-address` raised in the end-to-end profile |
| AC-8 — one address holds at most one live seat | integration test, at the production default of 1 |
| AC-8 — the endpoint answers 503 when the pool is empty | integration test, which leases every seat |
| AC-8 — `/demo` renders the seat-busy state | mocked inner-loop spec, over the real `ProblemDetail` shape; declared as a mocked seam |

The production default stays 1. Only the end-to-end profile raises the property. AC-7 already proves
its two 403 directions by integration test, so this journey uses one pattern for both. Each AC line in
the PR carries this qualification.

### The demo front-door — decided at carve time

S-136 specifies a bespoke signed `af_anon` cookie, and asks the tenant resolver to accept it as a second
tenant-context source. **This carve does not build that.** Measured reasons:

1. `SecurityConfig.java:74` ends with `anyRequest().authenticated()` over an `oauth2ResourceServer`
   JWT chain. A cookie principal needs a second authentication filter beside it.
2. `ClubTenantIdentifierResolver.java:32` returns empty for anything that is not a
   `JwtAuthenticationToken`. A cookie principal resolves `NO_TENANT` and reads nothing.
3. The `GUEST` role that already exists in `Role.java:14` grants nothing.
   `FlightsController.java:57` needs `CLUB_ADMINISTRATOR`, `FLIGHT_OPERATOR` or `PILOT`. So a cookie
   principal also needs a parallel authorization model, or a widened `@PreAuthorize` on every read.

**The decision: each seat holds a real Keycloak user with `CLUB_ADMINISTRATOR` on its own club.**
The demo-session endpoint performs a direct grant with server-held credentials and returns the access
token. Every seam downstream then works unchanged: the tenant resolver, `@PreAuthorize`, the audit
actor, and the just-in-time user materializer. `KeycloakAdminTokenSupplier.java:56` already holds the
client credentials.

### Prior art this journey composes, rather than builds

- `V14__deployment_lifecycle.sql:23-31` already inserts the sandbox Deployment at
  `00000000-0000-0000-0000-000000000001`. **No club binds to it** — `t_club.deployment_id` defaults to
  the operator Deployment `…0002`. This journey creates the seat clubs, not the Deployment.
- `DeploymentProvisioningService.provision` (`:55`) already creates a Club under a Deployment.
- `KeycloakAdminClient` already has `createUser` (`:53`), `deleteUser` (`:90`),
  `writeClubIdAttribute` (`:121`) and `grantRealmRoles` (`:277`).
- `LifecycleStateFilter` (S-137) exists in `deployments/application/`, with an aspect beside it. AC-10
  applies it; it does not build it.
- `JobRegistry` + `BusinessJob` + `@MeasuredJob` exist. The reset job registers, and Run-now drives it.

### The seed is production code, not a migration

`SandboxSeeder` is an idempotent production component, **parameterized by club**. Flyway creates the
seat clubs and the seat table. The reset job calls the same seeder, so the seeded state and the
reclaimed state cannot drift. This follows the `ShowcaseSeeder` precedent and ADR 0027 §3.

**Every seeded date is relative to the run date.** A seeded absolute date breaks on a future run date.
Rider R1 below covers the guard.

### Reachability — verified at carve time

- `/start` applies `tenantRequiredGuard`. A seat token carries `clubId` = that seat's club, so the
  guard passes, the same way `clubadmin1` passes it.
- `/system/jobs` applies `sysadminGuard` (`jobs.routes.ts:3`), and `jobs-console-parity.spec.ts:99`
  already drives it as `sysadmin`. AC-6 is reachable.
- The `[SYSTEM-ADMINISTRATOR-CANNOT-REACH-THE-AUDIT-SCREEN]` rider applies to `clubAdminGuard` on
  `/system/logs` only. It does not block AC-6.

## Tasks

**Agent budget.** A `/do-task` worker costs about 12 agents. A session caps at 200 agents, so it
finishes about **16 tasks**. This list holds **14**, so gate-surfaced work has about two slots of slack.
The release valve is the deferrable tail below.

- [x] **T-01** — Spec stub + the journey proof-gallery page. Selectors and flow, thin assertions. *(`869d2bea9` — 4 real-IdP + 4 mocked cases, each `test.fixme` naming its unskipping task; gallery `<h1>` = `J-20 — proof`.)*
- [x] **T-02** — Scope the per-push gate: prior journeys run mock-IdP; only J-20's spec runs real-IdP. Also move the frontmatter `parity_test:` mock-spec path to `mock_test:` (it warns on every push). *(the existing `derive-journey-lane.sh` mechanism, no new one; the wrong-key warning is gone; both lanes stay on their fail-safe until T-12 and T-14 unskip the cases.)*
- [x] **T-03** — `t_demo_seat` (platform table, no tenant column) + the N seat Clubs under Deployment `…0001` (Flyway). *(`V62__demo_seat_pool.sql` — 10 seats, clubs `DEMO01`..`DEMO10`, users `demo1`..`demo10`, every seat `FREE`. No UNIQUE index on `lease_holder_key`: the per-address cap stays the T-07 Java property.)*
- [x] **T-04** — `SandboxSeeder` masterdata, parameterized by club: locations, aircraft, persons. *(`SandboxSeeder.seed(seatClubId, seatNumber)` — 4 Swiss airfields, 4 aircraft and 6 members per seat, all written through `LocationsService` / `AircraftsService` / `PersonsService`, so a seeded row carries the invariants a real write produces. Every seat holds its own immatriculation block, because `ux_aircraft_immatriculation` is global. `SandboxSeederIT` seeds twice and asserts the counts do not change.)*
- [ ] **T-05** — `SandboxSeeder` operational data: flights over the last 30 days, reservations over the next 14 days, one planning day. Every date relative to the run date.
- [ ] **T-06** — The N `demo1..demoN` Keycloak users: `CLUB_ADMINISTRATOR`, `clubId` = their seat's club, plus the `t_user` rows.
- [ ] **T-07** — `DemoSeatLease` — lease a free seat under concurrency, one live seat per address, 503 when the pool is empty (AC-8). The per-address cap is the property `demo.max-live-seats-per-address`; see the AC-5/AC-8 decision below.
- [ ] **T-08** — `POST /api/v1/public/demo-session` — lease + direct grant + its `SecurityConfig` entry.
- [ ] **T-09** — The sandbox seal — both 403 directions of AC-7 + the S-024 leakage sweep extension. T-03 measured two call sites that read the seat clubs today: `ClubsController.java:42` (`listClubs`) and `SystemDashboardService.java:24-28`. Measure whether `listClubs` is tenant-scoped BEFORE you call either one a leak; then seal or exclude each one deliberately. T-04 measured a third call site: `Aircraft` carries no `@TenantId`, and `AircraftsTenantIsolationIT.java:47` asserts that the aircraft list returns every club's rows. So one demo visitor reads the fleet of every other seat and of every real club. Decide this one deliberately too.
- [ ] **T-10** — `SandboxResetJob` — reclaim expired seats (delete + re-seed per club) + the nightly full pass + the AC-9 isolation proof.
- [ ] **T-11** — `@LifecycleStateFilter` on the other registered jobs + the registry-scoring test of AC-10.
- [ ] **T-12** — Web: `/demo` replaces `DemoStubComponent`; start the session, land on `/start`; the seat-busy state, the demo banner, its call-to-action, and the funnel telemetry. Update the cross-journey consumers of the stub: `landing.spec.ts:133` (asserts `demo-stub` visible) and `demo.routes.ts:6`.
- [ ] **T-13** — Rider R1 — `[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]`.
- [ ] **T-14** — Thicken the real-IdP proof spec, including the two-visitor isolation assertion of AC-5, + the gallery captures.

### The deferrable tail

If the gate surfaces heavy unforeseen work, ship the demo with **flights, aircraft, persons and
locations only**. Defer the reservations and the planning day, and narrow AC-2 to the three entities
that ship. **Do not defer the reclaim half of T-10:** the demo user writes, so reclaim is the only thing
that returns a seat to the pool. A pool that never reclaims runs out and stays out.

## Riders

One rider rides this journey's gate. `/do-ship` deletes its bullet from `_BOYSCOUT.md` as it ships.

### R1 — [ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY] [S2]

J-19 T-21 widened the quote styles, the verbs and the call span of
`absolute-flight-date-in-api-seed-guard.mjs`, but the guarded field list is still the three flight
fields T-03 chose. A T-21 scan found absolute dates on other date fields that the same 90-day-window
hazard reaches: `reservations-migration-parity.spec.ts:305-381` seeds `start` / `end` at `2026-09-02`,
`deliveries-write-parity.spec.ts:519` seeds `deliveryDateTime` at `2026-06-01`,
`aircraft-migration-parity.spec.ts:312` seeds `atDateTime` at `2026-01-01`, and
`e2e/tests/email/notifications.spec.ts:48` seeds `SelectedDay` at `2026-06-15`. Answer per field: does a
server-side default window reach this date on a future run date? Then derive the date, or add the field
to `GUARDED_DATE_FIELDS`.

**Why it rides J-20.** `SandboxSeeder` writes exactly these field classes — flight dates, reservation
start and end, and a planning day — and it re-runs on every seat reclaim, so a seeded absolute date
rots on a schedule rather than once. *(seam: `GUARDED_DATE_FIELDS` + those four specs)*

## Riders NOT pulled — and why

Returned to `_BOYSCOUT.md` on 2026-08-23, when per-visitor tenancy took their budget (operator
decision): `[FAILED-ANONYMOUS-ROW-NAMES-NO-CLUB]` + `[REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT]`,
`[FORM-FIRST-PAINT-RED]`, `[PROD-DENSITY-ATTR-MISSING]`. All are S2 or S3 and none blocks this gate.
The audit-attribution pair also got **less** coupled: each seat now writes as a real, distinct Keycloak
actor, so demo writes attribute through the normal path, and the pair's concern stays on the rejected
anonymous public-registration write.

- **[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]** stays on J-21. The demo banner points at
  `/signup?intent=migrate`, which is the migrate arm, not the bare `/signup` → `/join` default.
- The CI-lane cluster is filed as **J-34**. Do not scatter it across feature journeys.

## Assumptions made

1. **Each seat holds a real Keycloak user with `CLUB_ADMINISTRATOR` on its own club**, not the bespoke
   `af_anon` cookie of S-136. Grounded in the three measurements above.
2. **The demo visitor can write.** `CLUB_ADMINISTRATOR` carries write authority, and a read-only demo
   would need a new role across every `@PreAuthorize`. So seat reclaim is mandatory, not deferrable.
3. **One club per seat, inside the one sandbox Deployment.** A Deployment is the billing and lifecycle
   unit, so a Deployment per visitor is the wrong grain. The Club is the tenant, per ADR 0008.
4. **The pool starts at 10 seats**, as a configuration property. The number is cheap to change.
5. **`/start` is the landing screen** after the demo session starts.
6. **The reclaim is proved through Run-now, not through the clock.** The nightly job ships, but the
   proof spec drives the J-15 console, so the assertion is deterministic.
7. **S-142's trial countdown does not ride this journey.** The sandbox Deployment carries `plan = FREE`
   and never expires. The countdown belongs to J-21.
