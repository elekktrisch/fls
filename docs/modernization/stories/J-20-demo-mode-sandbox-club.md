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

### Cross-tenant masterdata stops at the Deployment — operator decision, 2026-08-23

Aircraft are cross-tenant on purpose. ADR 0008 §77-99 (the S-058 amendment) removed `@TenantId` from
`Aircraft` for chartered tow planes, `tenant-rules.yaml:215-218` registers `kind: cross-tenant`, and
`AircraftsTenantIsolationIT.java:46-56` asserts the open read by name. Legacy agrees:
`AircraftService.cs:166-263` applies no club filter, and the legacy table carries no `ClubId`. So
AlpenFlight ports legacy correctly and there is no tenancy defect here.

J-20 adds a reader that neither legacy nor the amendment contemplated. Every earlier reader was an
authenticated member of a real club. A demo seat is a real Keycloak principal, so it inherits the open
read, and the bleed runs both ways: the 10 seats push 40 demo aircraft into every real club's aircraft
screen, and a demo visitor reads every customer's fleet and immatriculations.

**The decision: cross-tenant means cross-tenant inside one Deployment.** T-09 filters the aircraft read
by Deployment in both directions. A real club never reads a sandbox aircraft. A demo visitor never reads
a real fleet. ADR 0008 keeps its club-level rule unchanged — the sandbox Deployment simply made the
Deployment boundary meaningful, because no club bound to it until T-03. Each direction needs a negative
test that seeds a should-be-excluded row and asserts its absence
([[feedback_safety_claim_needs_negative_test]]).

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

**Nothing seeds the pool at startup.** `SandboxResetJob` (T-10) is the only production caller of
`SandboxSeeder`. Its first run seeds every free seat, because a never-seeded seat carries no seed from
the run date. So the end-to-end lane must drive `sandbox-reset` once before a visitor reads a demo
screen, or T-12 must add a startup runner beside `ShowcaseSeedRunner`.

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
- [x] **T-05** — `SandboxSeeder` operational data: flights over the last 30 days, reservations over the next 14 days, one planning day. Every date relative to the run date. *(`SandboxOperationsSeeder` — 24 flights on 6 flying days, 6 reservations and 1 planning day per seat, all written through `FlightsService` / `AircraftReservationsService` / `PlanningDaysService`. Every date derives from `LocalDate.now(clock)`; `SandboxSeederRunDateRelativeIT` pins a `Clock` years ahead and asserts the exact offsets, so an absolute date reds. The seat holds no planning-day assignment type, so the planning day carries no crew.)*
- [x] **T-06** — The N `demo1..demoN` Keycloak users: `CLUB_ADMINISTRATOR`, `clubId` = their seat's club, plus the `t_user` rows. *(the seats live in `realm-export.json` — the one realm source that `dev-up-nocompose.sh` and the CI real-idp lane both import — so no startup provisioner writes to Keycloak and the field-selective PUT trap cannot fire. The `t_user` row needs no new code: `JitUserMaterializerImpl.materialize` (`:47`) writes it on the first token use. `check-realm-shape.sh` cross-checks the 10 principals against `V62__demo_seat_pool.sql`, and the planted-drift selftest scores four drift classes.)*
- [x] **T-07** — `DemoSeatLease` — lease a free seat under concurrency, one live seat per address, 503 when the pool is empty (AC-8). The per-address cap is the property `demo.max-live-seats-per-address`; see the AC-5/AC-8 decision below. *(`DemoSeat` aggregate + `DemoSeatLeaseService`. The atomic claim is the `@Version` optimistic lock that `V62` names: each attempt runs in its own `REQUIRES_NEW` transaction, and a visitor that loses the race retries on the next free seat. The state machine, the expiry rule and the cap are Java methods, per ADR 0022 D2. An expired lease keeps the `LEASED` state — `isReclaimableAt` marks it, and T-10 returns it to the pool. Three properties: `demo.pool-size` 10, `demo.max-live-seats-per-address` 1, `demo.lease-idle-period` PT30M. `application-dev.yml` — the profile that the real-idp lane runs — raises the cap to 4, and `DemoSeatLeaseRaisedCapIT` boots that profile to prove the raise. Each of the four claims is proved red-first: the removal of `@Version` reds the 10-parallel-claim case with duplicate seats. **Stated limit:** two requests from one address that arrive at the same moment can both pass the cap gate, because READ COMMITTED hides the other transaction from each of them; the structural abuse ceiling stays the pool size.)*
- [x] **T-08** — `POST /api/v1/public/demo-session` — lease + direct grant + its `SecurityConfig` entry. **Measured at T-06:** no realm client enables `directAccessGrantsEnabled` today, and `check-realm-shape.sh:18,35,43` asserts `false` on `alpenflight-web`, `alpenflight-proffix` and `alpenflight-backend-admin`. So T-08 adds a dedicated confidential client for the grant, and its own guard line. The seat password is the single value `alpenflight-demo-seat-dev-2026!` in the committed export; T-08 owns the server property that reads it. **State how a production realm gets its seats and its credential.** A committed password is correct for the dev realm and the CI realm. It must not become the production path by silence. Say which it is, in the code and in the report. *(the new client `alpenflight-demo-seat` is confidential, direct-grant only, and `fullScopeAllowed=false` with a scope mapping of `CLUB_ADMINISTRATOR` alone — a leaked secret cannot mint a `SYSTEM_ADMINISTRATOR` token, measured against the running realm. The guard keeps its three `false` assertions and adds twelve more, plus the structural rule that `admin-cli` and `alpenflight-demo-seat` are the only clients with a direct grant, plus a cross-check that the server defaults in `application.yml` equal the realm values. Six new planted-drift cases. The cap keys on `ClientIpResolver` — moved to `platform/web/` — so a forged `X-Forwarded-For` from a public peer does not lift it. The server reads the credential from `demo.direct-grant.*`; with the `prod` profile active and either value still the committed dev one, the front door answers 503 and names the two environment variables. A failed grant returns the seat to the pool.)*
- ~~**T-09**~~ *(split at the sizing gate — it held four seams: the aircraft read, the AC-7 authorization boundary, the leakage sweep, and two operator-facing club reads.)*
- [x] **T-09a** — Aircraft: seal the sandbox Deployment in **both directions**, per the operator decision below. A real club never reads a sandbox aircraft; a demo visitor never reads a real fleet. One negative test per direction — each seeds a should-be-excluded row and asserts its absence. *(the boundary is one JPQL join in `JpaAircraftRepository`: the aircraft's managing club and the reading club must share a `deployment_id`. It applies to the four `isAuthenticated()` reads — list, type slice, picker, detail + state history. The `@aircraftAccess`-gated endpoints keep the unscoped load, because that gate already binds the caller to the managing club or to `SYSTEM_ADMINISTRATOR`; a demo seat is neither, so it gets 403 there. Aircraft keep no `@TenantId` and `tenant-rules.yaml:215-218` keeps `kind: cross-tenant` — cross-club reads inside one Deployment stay open, and a test asserts that too. Persons and PersonFlightTimeCredits are the other two `cross-tenant` entities; T-09b owns them.)*
- [x] **T-09b** — The demo principal's authorization boundary: both 403 directions of AC-7 + the S-024 leakage sweep extension. T-03 measured two call sites that read the seat clubs today: `ClubsController.java:42` (`listClubs`) and `SystemDashboardService.java:24-28`. Measure whether `listClubs` is tenant-scoped BEFORE you call either one a leak; then seal or exclude each one deliberately. **Also seal `Persons` (`tenant-rules.yaml:10`) and `PersonFlightTimeCredits` (`:174`)** — T-09a found they are the other two `cross-tenant` entities, so the operator's Deployment rule covers them too. Persons carries member names and email addresses, so an anonymous demo visitor reading a real club's roster is worse than the fleet case. Same bar: one negative test per direction, red first.
- [x] **T-10** — `SandboxResetJob` — reclaim expired seats (delete + re-seed per club) + the nightly full pass + the AC-9 isolation proof. *(`SandboxResetJob` registers as `sandbox-reset` in `JobRegistry`, carries `@MeasuredJob` and the nightly cron `0 45 3 * * *`. `/system/jobs` Run-now calls the same `runOnce()`, and the console renders every registered job, so no allowlist blocks it. `SandboxSeatResetService` resets each seat that holds no live lease: an expired lease always, a free seat only when its seed is not from the run date — so a Run-now during the day touches the expired seat alone. One seat runs purge → the SAME `SandboxSeeder.seed` → `returnSeatToPool`, each step in its own transaction, so a failed seat keeps its state and the next run retries it. `SandboxClubPurge` deletes 19 club-scoped entities in foreign-key-safe order through JPQL bulk deletes, each with an explicit club predicate (ADR 0027 — no native SQL); the constructor scores every `@TenantId` entity against that list and refuses to start on an entity the list does not name. **Measured:** `MutationAuditEvent` stays out of the purge — `V54__split_app_role_append_only_audit.sql:27` REVOKEs DELETE on `t_mutation_audit_event` from `alpenflight_app`, so a purge of the audit rows passes under the test role and 42501s in production. **Measured:** Hibernate applies the `@TenantId` filter to a bulk delete, and it binds the tenant when the session opens — a transaction boundary outside `Tenants.runAs` made every tenant-scoped delete match `NO_TENANT` and delete no row. AC-9 is proved red first: a delete widened to reach one aircraft of the operator-Deployment club reds the assertion `the cross-tenant aircraft of the operator-deployment club survives the reset`.)*
- [x] **T-11** — `@LifecycleStateFilter` on the other registered jobs + the registry-scoring test of AC-10. **`sandbox-reset` is the one exemption:** the job exists to write inside the sandbox Deployment, so the scoring test must name it as such and not demand a lifecycle filter on it. *(**The guard refuses to start, it does not only test.** `JobRegistry.java:103` scores the injected `List<BusinessJob>` — the one authority on what "registered" means — so a job registered as a `@Bean` lambda, behind an AOP proxy, or from the included-build jar cannot hide from it; a static source scan sees none of those three. **Four registration classes carry a planted violation, and the pre-T-11 static scan scores each one green:** an annotated bean that declares `@UnscopedScheduledJob` (three production jobs carried this shape), a job with no `@Scheduled` entry point that only Run-now calls, a lambda that a factory method returns, a subclass that inherits the `@MeasuredJob` name of its parent and registers as a second bean, an AOP proxy, and a filter that names `SANDBOX` itself. `JobRegistrySandboxDeploymentSealRedsOnEveryPlantedViolationTest.java:129` scores the old rule green and the seal red for each. **The jar class is the one class this task scores only on the old rule** — `ch.alpenflight.migration.bundle` sits inside the `ch.alpenflight` component-scan root but `DoNotIncludeJars` hides it; the seal reads instances, so origin cannot hide a bean, and the lambda plant proves that mechanism. **`licence-notification` and `aircraft-database-sync` were unsealed, and the fix is a narrowed query, not a lifecycle filter:** both sweep a cross-tenant table once per run, so per-club iteration would mail a person twice and fetch the OGN registry per club. `JpaPersonRepository.java:112` excludes a Person that holds a membership in a club of a sandbox Deployment; `JpaAircraftRepository.java:95` excludes an Aircraft whose managing club sits there. Both are red first: with the old query the demo seat member reads 2 mails and the demo seat aircraft takes the OGN device values. **A second exemption, and it is deliberate:** `client-ip-retention` redacts client IPs past the retention window, and `V54__split_app_role_append_only_audit.sql:27` REVOKEs DELETE on `t_mutation_audit_event`, so the sandbox purge cannot delete a demo visitor's audit row. Sealing that job would strand a real anonymous visitor's IP address forever. **Verified one level out:** the guard fires at Spring context start — one exemption removed reds `MeasuredJobAspectIT` with the full message — and the packaged `bootJar` answers `/actuator/health`.)*
- ~~**T-12**~~ *(split at the sizing gate — the contract fix, the screen and the cross-screen banner are three seams.)*
- [x] **T-12a** — **Fix the 503 contract first, then build against it.** `DemoSessionController.java:37-39` declares no content for 503, so `openapi.json` types that body as `DemoSessionResponse`, but the real body is `ProblemDetail` (`DemoSessionExceptionHandler.java:33`). The seat-busy state is AC-8, so the screen must not read a wrong type. Regenerate the snapshot and the client. *(the 503 now declares `application/problem+json` with a `ProblemDetail` schema. `OpenApiIT.java:95` scores the defect red on the old code: the live spec answered `application/json` and the `DemoSessionResponse` schema. The regenerated snapshot carries `#/components/schemas/ProblemDetail`, and orval emits `model/problemDetail.ts`, so the screen of T-12b reads the real shape.)*
- [x] **T-12b** — **`/demo` replaces `DemoStubComponent`.** Start the session, land on `/start`, render the seat-busy state of AC-8. Update the cross-journey consumers of the stub: `landing.spec.ts:133` (asserts `demo-stub` visible) and `demo.routes.ts:6`. *(`demo.page.ts` leases the seat on a start action — never on page load, because an auto-lease burns a seat per visit and the per-address cap of T-08 then refuses the visitor. `DemoSeatSession` (`core/session/demo-seat.session.ts`) holds the seat token for the tab, an interceptor rides it on every `/api/v1/` path except the anonymous public segment (an expired bearer 401s a permitAll path), and the claims of the token install the session through the same `applyClaimsToSession` the OIDC bridge uses. **The 3.3 s cold lease:** the action shows a loading control at once and a readable progress notice after 300 ms, per ADR 0024. Measured against the running stack: a cold lease took 3201 ms and the notice appeared; a warm lease took 768 ms. **Measured one level out, and it found a defect the unit specs and the mocked run both passed over:** `MeEventsService` reads its bearer from the OIDC library, so the demo visitor's event stream answered 401 on every screen. It now reads the seat token first; `me-events.service.spec.ts:148` scores the old code red. **Cross-journey consumers, from a repository-wide grep:** `landing.spec.ts:127` (now reads `demo-page`), `demo.routes.ts:6`, and the `publicStub` i18n block of all four locales, which the stub was the last reader of. **Unskipped:** the entry case and the seat-busy case of `demo-mode.spec.ts`. The two banner cases name T-12c, which ships the banner.)*
- [x] **T-12c** — **The demo banner across every demo screen (AC-3)** — its permanent text, its call-to-action to `/signup?intent=migrate`, and the funnel telemetry. *(**The banner rides the app shell, not the pages.** `app.component.ts:20` renders `<af-demo-seat-banner />` above the `<router-outlet />`, so every route carries it. A screen that a later journey adds inherits the banner and needs no edit. `demo-seat-banner.component.ts` reads the client-side demo signal T-12b already shipped — `DemoSeatSession.isLive` — so it renders nothing for a real club, and this journey invents no second signal. **A measurement corrected the layout, and it found a defect in the shell.** `af-nav-bar.component.ts:55` declares `sticky top-0`, but the nav bar scrolls away, because its host box is its own containing block and the sticky range is zero. `af-root` carried the same defect, so the banner also scrolled away. `app.component.ts:14` now makes the shell a block, and the banner holds the top of the screen. **The permanence assertion is not vacuous:** the first form of it passed over the non-sticky code, because a banner that scrolls away has a negative `y`. `demo-mode.spec.ts:177` now scores that code red at -341 px. **Funnel telemetry through the existing `emitFunnelEvent` seam, and no second mechanism:** `demo.session_started` at the lease (`demo.page.ts:152`) and `demo.signup_cta_click` at the banner action. Both are the events S-136 names. The text ships in all four locales under `demo.banner`. **Verified one level out, against the real chain** — Keycloak, the backend, the LAN Postgres and the 4201 SPA: a real seat principal read the banner on `/start`, `/flights`, `/aircraft` and `/reservations`, the action opened `/signup?intent=migrate`, and the three funnel events fired. `clubadmin1@example.com` read no banner on the same screens, and read only its own fleet. **Unskipped:** the two banner cases of `demo-mode.spec.ts`. A third case scores the negative direction: a club principal reads no banner on the same three screens.)*
- [ ] **T-13** — Rider R1 — `[ABSOLUTE-DATE-GUARD-READS-THREE-FIELDS-ONLY]`.
- [x] **T-14** — Thicken the real-IdP proof spec, including the two-visitor isolation assertion of AC-5, + the gallery captures. *(**All six cases of `demo-sandbox.spec.ts` pass against the real chain, locally**, in 2.2 min: real Keycloak, the packaged backend and the LAN Postgres. **AC-5 is not vacuous, and three assertions make it so:** the second visitor's logbook holds exactly the 24 rows of one seat, so a broken tenant filter reds at 48; the row identifier the first visitor changed is absent from the second logbook, and that row genuinely exists in the database; and the second seat's copy of the same seeded flight holds the seeded comment, never the value the first visitor wrote. The two visitors read their seat club from the token the tab holds, and the spec asserts the two clubs differ. **The stable row selector is the duration, not the list order.** Two seats render the newest flying day in different orders, because the list breaks a flight-date tie on a UUIDv7 that carries a random part. The three-hour cross-country glider flight is the one row every seat renders identically, so the spec reaches the flight through its `03:00` duration cell. **AC-2 needs the visitor to widen the date range:** `/flights` defaults to today..today for legacy parity, and the seeder writes no flight on the run date. **AC-2 counts reservations from the week grid over three week pages**, because the week grid renders a count for each aircraft and day, and 14 days reach three calendar weeks. **AC-6 needs an expired lease, and only the clock produces one.** `application-dev.yml` — the profile the end-to-end lane runs — now sets `demo.lease-idle-period` to PT30S beside the cap it already raises. The case then runs Run-now until the visitor reads the seeded value again, up to three runs, so it waits on the state of the application and never on a fixed sleep. The reset also returns every seat of the run to the pool. **The real-IdP seat-busy case is removed.** The AC-5/AC-8 decision below gives that proof to an integration test and to the mocked spec, and a real-chain case cannot exhaust a 10-seat pool from one address at a cap of 4. Its gallery declaration in `ci.yml` is removed with it, so the page declares no shot the run cannot produce. **Five gallery captures, each on the state that renders the asserted result:** the dashboard with its tiles, the logbook with 24 rows over 30 days, the changed comment, the second seat's seeded comment, and the restored comment after the reclaim.)*
- [x] **T-15** — **S1. Seal the orphan Person.** A demo seat must not reach a Person that holds no club membership. See "Gate findings" below for the exploit chain and the missing test. Ship the negative test the current suite does not have: seed an orphan Person in the operator Deployment, then assert a seat can neither read it nor attach to it. *(**Measured red first:** a seat read a membership-less Person through `/persons/lookup`, by email and by identity triple. The same seat attached that Person through `/persons/{id}/clubs`, and the attach wrote a sandbox membership. The attach also destroys data: `SandboxClubPurge.java:56-62,78` deletes each Person that the seat club shares with no other club, so the next seat reset deletes the stolen Person row. `JpaPersonRepository.java:25-39` now admits a membership-less Person only to a reading club of a Deployment that is not a sandbox. The real direction stays open: a club of the operator Deployment still finds such a Person and still attaches it, so the create-then-attach flow and the migrated-person claim keep working. `PersonsService.java:95-106` refuses a create that the creating club cannot read back, so a seat writes no membership-less Person; such a row holds no club, so `SandboxClubPurge` cannot delete it and every real club reads it. `PersonsDeploymentIsolationIT` deletes every Person it writes, and the memberships cascade. **Open, and outside this task:** two real Deployments still read each other's membership-less Persons. `t_person` holds no Deployment column, so the predicate cannot tell the two apart. A seal for that needs a new column, a backfill and a `MapperLegacyBindings` change.)*
- [x] **T-15b** — **S1. Contain the real-to-real orphan leak.** Operator decision, 2026-08-24: ship the cheapest
  containment that needs NO schema change, and route the structural fix to J-21 as an S1 rider. A second real
  Deployment is self-service reachable — `MigrationBundleController.java:28-34` is not profile-gated and needs only
  `isAuthenticated() and email_verified`, and it provisions a trial Deployment through
  `MigrationBundleIngestService.java:489`. From it, a caller reads and steals every membership-less Person of every
  other real Deployment. **Do not break** the fan-out parity fixture, which POSTs a bundle every run
  (`fan-out-parity-fixture.ts:233`), nor the create-then-attach flow that T-15 kept open.
  *(**Measured, then shipped:** three containment options were scored. **Provenance is dead:**
  `t_person.created_by_user_id` names no Deployment — the producer drops the legacy system user
  (`MapperLegacyBindings.java:18-19,171`) that stamps 6 of the 7 legacy Persons of the e2e seed, and
  `Person.java` maps no such field, so every API-created Person holds NULL. **Gating the bundle endpoint is
  rejected:** the parity fixture POSTs a bundle every run, and a gate there seals no legitimate second
  Deployment. **Shipped — the endpoint narrowing:** `JpaPersonRepository.java:37-38` reaches a Person by
  e-mail (`:89`) and by identity triple (`:98`) only through a club membership of the reading club's
  Deployment. The id read keeps the membership-less branch (`:40-42`, `:107`), so
  `PersonsService.java:101` still reads a new Person back and `:252` still attaches one. Red first:
  `PersonsDeploymentIsolationIT.java:233` scored the old code red, because a club of a second real
  Deployment read the orphan by e-mail. All 11 cases of that IT pass now, and the IT deletes the Persons,
  the clubs and the Deployment it writes. **Open, and routed to J-21 as
  `[ORPHAN-PERSON-ATTACHES-ACROSS-REAL-DEPLOYMENTS]`:** a caller that holds a Person id still attaches a
  membership-less Person from any Deployment that is not a sandbox. The identity search also stops reaching
  a membership-less Person inside the owning Deployment; the person-picker of the user screen
  (`person-picker.component.ts`) is the only client of that search, and no client calls the attach
  endpoint.)*
- ~~**T-16**~~ *(split at the sizing gate — it held three seams: a production fail-open filter, two tautological test suites, and the entity catalog. F4 is a live correctness defect, not a test defect, so it gets its own worker.)*
- [x] **T-16a** — **F4. `DemoSeatPrincipalBinding.pool()` failed open.** The cache now keeps only a read that found a seat, so an empty read re-arms the filter on the next request. `DemoSeatPrincipalBindingTest.java:26` scores the defect red on the old code.
- [ ] **T-16b** — **F2 + F5. Two suites that score nothing.** `LeakageSweepIT.java:105-147`'s 36 sandbox cases pass on pre-J-20 code and exclude the three entities the seal is about. `DemoSeatPrincipalBindingIT.java:326-337` carries AC-7's headline name and passes on pre-J-20 code. Score a planted violation per input class, or withdraw the class.
- [ ] **T-16c** — **F3. The entity catalog cannot see the bypass.** `TenantScopedEntityCatalog.java:43` collects only entities that already carry `@TenantId`, so `PersonClubMembershipOutsideTheTenantFilter` is invisible to the sweep, to the floor test and to `tenant-rules.yaml`.
- [x] **T-17** — **The front door seeds the seat it leases.** `DemoSessionStarter.java:58` seeds the leased seat before it asks the realm for the token, so the visitor cannot read a half-populated club: the token is the only door into the seat, and the seed commits first. The path is `SandboxSeatResetService.seedTheLeasedSeatUnlessItAlreadyHoldsTheSeedOfTheRunDate`, which reuses the purge and the seeder of the reset job. No startup seeder runs, so a boot, a test context and the packaged jar cost nothing. A cold lease of an empty seat club costs 3.3 s and a warm lease costs 0.14 s, measured against the packaged jar and the real realm. `DemoSessionControllerIT.java:291` reads the row counts back from the database.

- [x] **T-18** — **The branch is RED. Start here.** Two T-09b test fixtures write rows and never delete them, so they contaminate later classes on the shared Postgres. Give `SystemDashboardControllerIT.java:223-237` (`seedAircraft`, a raw INSERT, and the class holds no `@AfterEach` or `@AfterAll`) and `TwoClubFixture.java:85-97` (`seedAdditionalClubInDeployment`) an `@AfterEach` that deletes what each one writes.

- [x] **T-19** — **The fourth instance of the leak class.** `DemoSessionControllerIT.java:182,184` wrote two locations into seat clubs and deleted neither. The class now records each location it writes and deletes it after each test. `SandboxSeederIT.java:93` deleted every location of the seat club before each case, so the seeded airfield count could not score a stray. The reclaim now deletes only the four ICAO codes the seeder writes, on the same pattern as the immatriculation loop above it. `SandboxResetJobIT.java:141` strands nothing: the reset job under test removes the visitor airfield, and `SandboxResetJobIT.java:156-160` asserts it.

- [ ] **T-20** — *Deferrable.* **A structural guard for the contamination class.** A `LauncherSessionListener` at fork shutdown asserts that no seat club holds a row the seeder did not write, and that no non-seat club sits in the sandbox Deployment. It is order-independent, which `@AfterEach` is not, and it covers classes that share no base class. Four instances in this journey (T-05, T-07, T-18, T-19) argue for it. It blocks no gate, so it rides behind T-14.

### Resume order — severity first

**Reordered 2026-08-24 — done-bar path first.** The severity-first order put three guard-repair tasks ahead of
the screen this journey exists to ship. Severity ranks the S1s, and both are done; among what remains, the done
bar outranks test-quality repair.

`T-18` ✔ → `T-19` ✔ → `T-15` ✔ → `T-16a` ✔ → `T-15b` ✔ → `T-17` → `T-12a` → `T-12b` → `T-12c` → `T-11` →
`T-14` → §4 gate. Then, if budget remains: `T-16b` → `T-16c` → `T-13` → `T-20`.

**Budget tension, stated rather than hidden.** About 10 task-slots remain and the done-bar path plus the gate
spends about 9 of them. So `T-16b`, `T-16c`, `T-13` and `T-20` are unlikely to fit. `T-16b` and `T-16c` repair
the very guards that should have caught F1 — the leakage sweep scores nothing for a Deployment defect, and the
entity catalog cannot see a tenant table that carries no `@TenantId`. If they do not fit, they re-ride rather
than get dropped, and the operator decides.

### The live red, measured 2026-08-24

`alpenflight build (server + migration-tool)` fails, so `required` fails. Three tests:

| Test | Asserts | Reads |
| --- | --- | --- |
| `SandboxSeederIT.java:109` | seat-1 club manages 4 aircraft | 5 |
| `SandboxSeederIT.java:220` | the same count after a second seed | 5 |
| `DemoSeatPoolMigrationIT.java:154` | the sandbox Deployment holds seat clubs only | `IT_PC_s`, `IT_ACs`, `IT_PDs` |

`ch.alpenflight.me.*` sorts before `ch.alpenflight.tenancy.*`, so the contamination is deterministic, not
order-luck. The job is **green on `main`** (run 32634779006) and the pre-T-09b branch run 32675625506 was
green, so this is journey-caused. It is still live on `cf08f748b` (run 32703247626); T-10 did not touch
it and `3b305b198` is docs-only.

This is the third time this journey met the same class of defect — T-05 fixed `ShowcaseSeederIT` deleting
locations across every club, and T-07's finisher fixed the lease ITs leaving every seat `LEASED`. The
seat clubs make previously-invisible test contamination visible, because a stray row now lands inside an
exactly-counted pool.

**Budget.** This session ran 13 workers and stopped here, on the operator's instruction, with everything
pushed. A `/do-task` worker costs about 12 agents, so the 7 remaining tasks need a fresh session. Nothing
is in flight, the tree is clean, and every completed task carries its commit on `integration/J-20`.

## Gate findings — recorded 2026-08-24, before the session boundary

A `gap-hunter` round attacked the tenancy seal of T-09a and T-09b. It gave a clean bill to the aircraft
seal, the clubs and dashboard reads, `PersonFlightTimeCredits`, and the reachability of the bypass view
`PersonClubMembershipOutsideTheTenantFilter` — that view has no repository, no service, no projection and
no controller path today. The findings below are what it broke. **Each cause is its own claim: confirm or
refute it against the tree before you fix it.**

### F1 — the orphan Person is readable, and stealable [S1 → T-15]

`JpaPersonRepository.java:24-26` holds `or not exists (…)`, which returns every membership-less Person to
**every** Deployment, unconditionally. The chain:

1. A real club creates a person and omits `initialClubMembership` — it is `@Nullable` at
   `PersonDtos.java:149` and the server requires nothing.
2. A visitor leases a seat. That `CLUB_ADMINISTRATOR` token posts `/api/v1/persons/lookup` with the email,
   or with firstname, lastname and birthday, and reads name, birthday and email (`PersonDtos.java:233-240`).
3. The same token posts `/api/v1/persons/{id}/clubs`. `PersonsService.java:238` admits it through the same
   predicate. The response carries address, phones, licences and medical expiry, **and the call writes a
   sandbox membership**. The person is no longer an orphan, so the real club can never look it up or
   attach it again.

Orphans are not rare: `MapperLegacyBindings.java:97` migrates `SELECT … FROM Persons` with no membership
filter, so every legacy person with zero `PersonClub` rows arrives orphaned.

**The "pinned by a test" claim is false for the dangerous direction.**
`PersonsDeploymentIsolationIT.java:169` runs `realClubA → realClubA` only. No test seeds an orphan and
asserts that a seat cannot read it.

### F2 — the leakage sweep's sandbox cases are tautological [S2 → T-16]

`LeakageSweepIT.java:105-147` adds 36 sandbox cases by substituting `sandboxSeatClub` for `clubB` in the
pre-existing `tenant_scoped_create_in_A_invisible_to_B` (`:65`). `@TenantId` keys on `club_id`, so a seat
club is only another club. Every one of the 36 passes on pre-J-20 code and none can red for a
Deployment-boundary defect. The cases also exclude the three entities the seal is about — Aircraft,
Person and PersonFlightTimeCredit. Score a planted violation per input class, or withdraw the class.

### F3 — the entity catalog cannot see the bypass [S2 → T-16]

`TenantScopedEntityCatalog.java:43` collects only entities that already carry `@TenantId`, and nothing
requires an `@Entity` on a tenant-owned table to carry one. So
`PersonClubMembershipOutsideTheTenantFilter` — mapped to `t_person_club` with no discriminator — is
invisible to `LeakageSweepIT`, to the floor test and to `tenant-rules.yaml`. The next repository or reused
JPQL over it reads every club's roster with zero reds. `@Immutable` also stops UPDATE only; it does not
stop `persist` or `remove`, so read-only is convention, not enforcement.

### F4 — the AC-7 filter failed open [S2 → T-16a] — CONFIRMED, cause corrected

The mechanism is real. `DemoSeatPrincipalBinding.java:43-51` (not `:221-229`) cached the first `pool()`
load forever, an empty map included. An empty first read left `refusesPrincipalCarryingClub` returning
`false` for every principal, for the life of the JVM.

Two parts of the claim are wrong. The trigger was latent, not live: `V62__demo_seat_pool.sql:66` seeds
ten seats, every profile sets `spring.flyway.enabled: true`, and no code deletes a `t_demo_seat` row.
`SandboxSeatResetService.java:91` deletes the rows *of* a seat club and keeps the seat. A test also
pinned a non-empty pool already — `DemoSeatPoolTestFixture.java:27` fails when seat 1 or seat 2 is absent.

Spring Boot 4.0.6 starts the web server inside `refreshContext` and calls each `ApplicationRunner`
after it. A startup seeder on the shape of `ShowcaseSeedRunner.java:15` runs while Tomcat already serves
requests, and a request in that window reads an empty pool. T-17 therefore adds no startup seeder: the
front door seeds the seat it leases, so no window exists.

The fix caches only a read that found a seat. An empty pool holds no seat club and no seat principal, so
the filter refuses nobody while it is empty, and it re-arms at the first read that finds a seat.

### F5 — one AC-7 case proves nothing [S3 → T-16]

`DemoSeatPrincipalBindingIT.java:326-337` carries AC-7's headline name, but it passes on pre-J-20 code:
`ClubsController.java:67-69` already gates `getClub` with `@tenant.isOwnClub(#id)`. Three refusals were
measured red, not four. AC-7 stays covered by `:305`; this case is decoration.

### The deferrable tail

If the gate surfaces heavy unforeseen work, ship the demo with **flights, aircraft, persons and
locations only**. Defer the reservations and the planning day, and narrow AC-2 to the three entities
that ship. **Do not defer the reclaim half of T-10:** the demo user writes, so reclaim is the only thing
that returns a seat to the pool. A pool that never reclaims runs out and stays out.

## T-14 findings — measured against the real chain

### F6 — the demo dashboard shows one zero tile, so AC-1 is half met [S2]

AC-1 asks for dashboard tiles that are not zeros. The demo dashboard reads **Flights today 0** and
**Flights to validate 24**. `SandboxOperationsSeeder.java:107` writes flying days at 2, 5, 9, 12, 19 and
26 days **before** the run date, and none on it, so `FlightsService.clubFlightCounts` (`:157`) counts
zero for the run date. The proof spec asserts what the seed genuinely produces: the validation tile reads
at least 24, and the today tile renders a real count instead of a load error.

The fix is one entry in the flying-day list, plus the flight counts of `SandboxSeederIT.java:109,220` and
the offsets of `SandboxSeederRunDateRelativeIT`. That is the T-05 seam and a full server `check`, so T-14
did not take it. **Decide: add a flying day on the run date, or narrow AC-1 to one tile.**

### F7 — a demo visitor reads the fleet of every other demo seat [S3]

Measured over the real chain: with two seats seeded, `/api/v1/aircraft` returns **8** aircraft to seat 1,
and 4 of them belong to seat 2. Aircraft carry no `@TenantId`, T-09a seals the read at the Deployment, and
every seat club sits in the one sandbox Deployment. So a demo visitor reads up to 40 aircraft with a pool
of 10. Flights, reservations and persons stay private, because they carry `@TenantId`.

AC-2 asks for at least 3 aircraft, so the proof passes, and the gallery caption claims no privacy for the
aircraft register. **Decide: give each seat its own Deployment, filter the sandbox aircraft read by club,
or accept the shared fleet and say so on the screen.**

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
