---
id: J-20
title: Demo mode — a visitor with no account enters a populated sandbox club (/demo)
epic: E-15
status: todo
journey0: false
hardening: false
carved: true
depends_on: [J-2, J-3, J-5, J-15]
rolls_up: [S-135, S-136]
acceptance:
  - "[happy] AC-1 — A visitor with no account opens `/`, selects the demo call-to-action, and lands on `/start`. The dashboard tiles show sandbox data, not zeros."
  - "[happy] AC-2 — The demo visitor opens `/flights` and reads at least 20 flights dated in the last 30 days. The visitor opens `/aircraft` and reads at least 3 aircraft. The visitor opens `/reservations` and reads at least 5 reservations dated in the next 14 days."
  - "[happy] AC-3 — Every demo screen shows a permanent banner. The banner tells the visitor that the data resets each night. The banner call-to-action opens `/signup?intent=migrate`."
  - "[happy] AC-4 — The demo visitor changes a sandbox flight. The change stays after a page reload."
  - "[happy] AC-5 — A system administrator runs the `sandbox-reset` job from `/system/jobs`. The demo visitor's change is gone. The seeded value is back."
  - "[key-error] AC-6 — A demo principal that reads a club outside the sandbox Deployment gets 403. A principal that is not the demo user, but carries the sandbox club, gets 403. (Proved by an integration test, not by the end-to-end run — the second principal needs a minted token.)"
  - "[key-error] AC-7 — The demo-session endpoint answers 429 to a caller above the per-address rate limit."
  - "[edge] AC-8 — The reset deletes no row outside the sandbox Deployment. The test seeds a club row in the operator Deployment first, and asserts that the row survives the reset."
  - "[edge] AC-9 — Every registered `BusinessJob` skips the sandbox Deployment. A registry test scores each job for the lifecycle filter and reds on a job that has none."
screen: /demo (new) → /start, /flights, /aircraft, /reservations in demo mode — replacing the `DemoStubComponent` placeholder
headless_pulled_in: "SandboxResetJob → `/system/jobs` Run-now (the J-15 admin console); sandbox seeder → the same job"
migration: "N/A — greenfield"
parity_test: alpenflight/web/e2e/tests/real-idp/demo-sandbox.spec.ts (new) + alpenflight/web/e2e/tests/demo/demo-mode.spec.ts (mocked inner loop, new)
adr_refs: [0007, 0008, 0018, 0022, 0024]
---

## Context

The J-16 landing page ships a demo call-to-action. It opens `/demo`, and `/demo` renders
`DemoStubComponent` — 42 lines that say nothing and link back to the landing page. So the second arm of
the funnel dead-ends today. This journey makes the call-to-action deliver the product: a visitor with no
account opens a full AlpenFlight club, filled with realistic Swiss-club data, and can change it. A job
resets the data each night.

## Spec must assert

The proof spec drives the real identity provider. It uses two principals in one run, as
`jobs-console-parity.spec.ts` already does.

**Happy path.** The spec opens `/` as a visitor with no session. It selects
`[data-testid="landing-cta-demo"]`. The application lands on `/start`. The dashboard tiles read
non-zero. The spec opens `/flights`, `/aircraft` and `/reservations` and asserts the row counts of AC-2.
The demo banner is visible on each screen.

**The reset is the load-bearing proof.** The spec changes a flight as the demo visitor and re-reads it.
Then it logs in as `sysadmin`, opens `/system/jobs`, and runs `sandbox-reset` through Run-now. Then it
re-reads the flight as the demo visitor and asserts the seeded value is back. A screenshot of the
changed row and a screenshot of the restored row both land in the gallery
([[feedback_proof_artifact_must_render_result]] — the capture must show the asserted result).

**Key errors.** The demo-session endpoint answers 429 above the rate limit. The cross-Deployment
rejections of AC-6 are proved by integration tests, because the second principal needs a minted token.

**The reset must not reach outside the sandbox.** The isolation test seeds a club row in the operator
Deployment (`00000000-0000-0000-0000-000000000002`), runs the reset, and asserts the row survives. An
assertion that only counts sandbox rows passes vacuously
([[feedback_adversarial_seed_for_narrowing_assertions]]).

## Notes

### No design reference exists for this screen

`docs/modernization/design-reference/` holds no demo or sandbox screen. `screens-public.jsx` covers the
public registration forms only. So there is no pixel oracle for the `/demo` entry page or the demo
banner. Build both to ADR 0024 (§11 of `alpenflight/web/CLAUDE.md`): flat, sharp corners, slate
neutrals, brand color on the action only, sentence case, terse Swiss-impersonal voice. The demo banner
is new chrome — keep it one line, `slate-100` surface, one text link.

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

**The decision: one shared Keycloak `demo` user, with `CLUB_ADMINISTRATOR` on the sandbox club.**
`POST /api/v1/public/demo-session` performs a direct grant against Keycloak with server-held
credentials and returns the access token to the single-page application. Every seam downstream then
works unchanged: the tenant resolver, `@PreAuthorize`, the audit actor, and the just-in-time user
materializer. `KeycloakAdminTokenSupplier.java:56` already holds the client credentials, so the new
code is one endpoint, not a front-door.

**The cost, stated plainly.** Every demo visitor shares one identity, so concurrent visitors see each
other's changes. That is not a regression: S-135 already puts every visitor in ONE sandbox Deployment
with shared clubs, so the bespoke cookie separated identities but never separated data. The one thing
lost is per-visitor audit attribution inside the sandbox.

**The operator can overturn this at `/do-ship`.** The bespoke cookie is about five more tasks and adds
a second authentication front-door.

### The sandbox Deployment already exists

`V14__deployment_lifecycle.sql:23-31` inserts the sandbox Deployment at
`00000000-0000-0000-0000-000000000001`. **No club binds to it** — `t_club.deployment_id` defaults to the
operator Deployment `…0002`. So this journey creates the sandbox club rows, not the Deployment.

### The seed is production code, not a migration

`SandboxSeeder` is an idempotent production component. Flyway creates only the sandbox Club rows. The
reset job calls the same seeder, so the seeded state and the reset state cannot drift. This follows the
`ShowcaseSeeder` precedent and ADR 0027 §3.

**Every seeded date is relative to the run date.** A seeded absolute date breaks on a future run date.
Rider R1 below covers the guard.

### Reachability — verified at carve time

- `/start` applies `tenantRequiredGuard`. The demo token carries `clubId` = the sandbox club, so the
  guard passes, the same way `clubadmin1` passes it.
- `/system/jobs` applies `sysadminGuard` (`jobs.routes.ts:3`), and
  `jobs-console-parity.spec.ts:99` already drives it as `sysadmin`. AC-5 is reachable.
- The `[SYSTEM-ADMINISTRATOR-CANNOT-REACH-THE-AUDIT-SCREEN]` rider applies to `clubAdminGuard` on
  `/system/logs` only. It does not block AC-5.
- `LifecycleStateFilter` (S-137) exists at
  `deployments/application/LifecycleStateFilter.java`, with an aspect beside it. AC-9 applies it; it
  does not build it.
- `JobRegistry` + `BusinessJob` + `@MeasuredJob` exist. The reset job registers, and Run-now drives it.
- `PublicRegistrationAbuseGuard` exists and is called from `PublicRegistrationIntake.java:38,56`. AC-7
  reuses it.

### Likely task seams (non-binding, for `/do-ship`)

One aggregate, one component, or one resource's endpoints per seam.

1. Sandbox Club rows (Flyway) + `SandboxSeeder` masterdata — locations, aircraft, persons.
2. `SandboxSeeder` operational data — flights over the last 30 days, reservations over the next 14
   days, one planning day. **All dates relative.**
3. Keycloak `demo` realm user + role + `clubId` attribute + the `t_user` row.
4. `POST /api/v1/public/demo-session` — direct grant, rate-limited, plus its `SecurityConfig` entry.
5. The sandbox seal — the two 403 directions of AC-6 + the S-024 leakage sweep extension.
6. `SandboxResetJob` — `BusinessJob` + `@MeasuredJob` + `@LifecycleStateFilter({ SANDBOX })`, nightly.
7. The reset isolation proof — the adversarial non-sandbox row of AC-8, plus change→reset→restored.
8. `@LifecycleStateFilter` on the other registered jobs + the registry-scoring test of AC-9.
9. Web — `/demo` replaces `DemoStubComponent`; start the session, land on `/start`; the demo banner,
   its call-to-action, and the funnel telemetry events.
10. The mocked inner-loop spec.
11. The real-identity-provider proof spec + the gallery captures.

### The deferrable tail

If the gate surfaces heavy unforeseen work, ship the demo with **flights, aircraft, persons and
locations only**. Defer the reservations and the planning day, and narrow AC-2 to the three entities
that ship. Do not defer the reset job: the demo user writes, so the reset is the only thing that stops
the sandbox from filling with visitor data.

## Riders

These four ride this journey's gate. `/do-ship` deletes each bullet from `_BOYSCOUT.md` as it ships.
Four riders against eleven feature seams is about 27% technical debt, inside the 40% budget
([[feedback_journey_is_a_60_40_sprint]]).

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
start and end, and a planning day. The journey is the first consumer that must get them all right.
*(seam: `GUARDED_DATE_FIELDS` + those four specs)*

### R2 — [FAILED-ANONYMOUS-ROW-NAMES-NO-CLUB] + [REQUEST-TENANT-HINT-HAS-NO-PRODUCER-LEFT] [S2]

One decision, two bullets. J-33 T-04 gave the rejected anonymous write the `ANONYMOUS_PUBLIC` kind. The
row still names no club and keeps no client address. Measured on `integration/J-33`: the 429 row reads
`tenant_club_id = null` and `client_ip = null`. Two causes sit outside T-04's seam.
`PublicRegistrationIntake.java:56` runs the abuse guard before `PublicClubResolver.resolve`, so the
rejection holds the slug and never the club id. And `Tenants.runAs` restores the request hint in its
`finally` block, so `RequestAuditFilter.java:80` always reads null.

The second bullet owns that null. T-03 deleted `AuditTargetTenantInterceptor`, the only writer of a
`RequestTenantHint` attribute that outlives the handler. So
`RequestTenantHint.currentForRequest` now always answers null, and the `targetTenantHint != null` branch
at `RequestAuditFilter.java:80-91` is unreachable. **Decide:** delete `RequestTenantHint` and that
branch, then drop `RequestAuditFilter` from the `TenantsRunAsAllowlistTest` allow-list — or keep the
hint and give it a producer. ADR 0008 §Amendment S-159 names `RequestAuditFilter` as an in-process
`runAs` seam, **so the deletion needs the operator.**

**Why it rides J-20.** The demo user writes to a real club through a real audit path. A demo write that
records no club is a hole in the same seam, and S-136 requires the audit log to name the demo identity.
This journey is where the decision pays for itself.
*(seam: `RequestTenantHint` + `RequestAuditFilter` + `PublicRegistrationIntake` +
`TenantsRunAsAllowlistTest`)*

### R3 — [FORM-FIRST-PAINT-RED] [S3]

`liveFieldErrors` (`shared/util/form/inline-validation.ts`) reports from first paint, so a blank form
opens fully red before the user types. J-17 T-17 gated each message on `events` locally in
`registrant-fieldset.component.ts`. The utility has **8 other consumers**, so every blank create form in
the application plausibly opens showing all its validation errors. Fix it in the utility, with an
opt-in for any screen that genuinely wants eager reporting. Check the shipped create screens before you
call it cosmetic.

**Why it rides J-20.** The demo is the first thing a prospective customer sees. A create form that opens
fully red is the worst possible first impression, and the demo visitor reaches those forms directly.
*(seam: `inline-validation.ts` + its 8 consumers)*

### R4 — [PROD-DENSITY-ATTR-MISSING] [S3]

`alpenflight/web/src/index.prod.html` never sets `data-density`, so the approximately 15
`body[data-density='comfortable']` rules in `styles.css` are inert in production while they apply in
development. The shipped application is denser than the one anyone reviews.

**Why it rides J-20.** The demo is a production surface that a prospective customer judges on looks. The
demo must render the same way the operator reviewed it.
*(seam: `index.prod.html` + the density rules in `styles.css`)*

## Riders NOT pulled — and why

- **[BARE-SIGNUP-JOIN-FUNNEL-UNCOVERED]** stays on J-21. The demo banner points at
  `/signup?intent=migrate`, which is the migrate arm, not the bare `/signup` → `/join` default the
  rider names.
- **[NAV-OVERLAY-EATS-CLICKS]** and **[MOCK-CLUB-ID-SHAPE]** touch this surface but push the budget
  past 40%. Pull them only if the gate hits them; both stay in `_BOYSCOUT.md`.
- The CI-lane cluster — **[NG-LINT-COVERS-TWO-E2E-DIRECTORIES-ONLY]**,
  **[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]**, **[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]**,
  **[EXTRACT-LANE-REDS-NOTHING-A-MERGE-DEPENDS-ON]**, **[ARCHUNIT-AND-NULLAWAY-DEMO-GATES-NEVER-RUN]**,
  **[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]** — is one coherent sweep. The J-33 retro routed it to a
  hardening journey. Do not scatter it across feature journeys.

## Assumptions made

1. **The demo principal is a shared Keycloak `demo` user with `CLUB_ADMINISTRATOR` on the sandbox
   club**, not the bespoke `af_anon` cookie of S-136. Grounded in the three measurements above. The
   operator can overturn this at `/do-ship` for about five more tasks.
2. **The demo visitor can write.** `CLUB_ADMINISTRATOR` carries write authority, and a read-only demo
   would need a new role across every `@PreAuthorize`. So the reset job is mandatory in this journey,
   not deferrable.
3. **One sandbox club, not several.** S-135 says "1..N, start with 1". The multi-club picker of S-136
   is dropped; the demo token carries the one sandbox club.
4. **`/start` is the landing screen** after the demo session starts. It is the same screen a real club
   administrator sees at login.
5. **The reset is proved through Run-now, not through the clock.** The nightly cron ships, but the
   proof spec drives the J-15 console, so the assertion is deterministic.
6. **S-142's trial countdown does not ride this journey.** The sandbox Deployment carries `plan = FREE`
   and never expires. The countdown belongs to J-21.
