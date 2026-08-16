---
id: J-19
title: Password recovery + email confirmation pages
epic: E-12
status: in_progress
started_at: 2026-08-16
journey0: false
hardening: false
carved: true
depends_on: [J-16]
rolls_up: [S-100]
acceptance:
  - "[happy] AC-1 — `/lostpassword` renders without a session and its primary action moves the browser to a Keycloak URL. Assertion: `account-recovery.spec.ts` — goto `/lostpassword`, `lostpassword-page` visible, click `lostpassword-start`, `waitForURL(/\\/realms\\/alpenflight\\//)`."
  - "[happy] AC-2 — An ephemeral user completes the reset chain and signs in with the NEW password. Assertion: same spec — create the user through `keycloak-admin.createUser`, submit the address on the Keycloak reset form, `waitForExactlyOneMessage` in Mailpit, follow the link, set the new password, then sign in with it and assert `landing-topbar-sign-in` has count 0."
  - "[key-error] AC-3 — The OLD password stops working after the reset. Assertion: same spec — `fillKcLogin(ephemeral.email, oldPassword)` keeps the URL on `/realms/alpenflight/login-actions/authenticate` and `KC_ERROR_SELECTOR` is visible."
  - "[key-error] AC-4 — A second use of the same reset link does not authenticate the user, and the Keycloak page returns the user to `/lostpassword`. Assertion: same spec — visit the link again, follow the theme back link, assert the pathname is `/lostpassword` and `lostpassword-page` is visible."
  - "[happy] AC-5 — A verify-email link opened in a session-less browser lands on `/confirm` in the verified state with a sign-in action. Assertion: same spec — open the Mailpit verify link in a fresh `browser.newContext()`, follow the theme back link, assert `confirm-outcome-verified` visible and `confirm-sign-in` visible."
  - "[happy] AC-6 — The register verify-mail chain runs green with the real Keycloak SMTP path. Assertion: `register.spec.ts` happy path loses `@quarantine-kc26` and keeps its existing `/migrate/start` + `migrate-start` assertions."
  - "[edge] AC-7 — Both new routes are public. Assertion: `public-routes.spec.ts` — an unauthenticated goto of `/lostpassword` and of `/confirm` renders each page testid and the URL never enters `/realms/`."
  - "[edge] AC-8 — Both pages fit a 360 x 640 portrait viewport and their actions meet the touch-target rule. Assertion: same spec at that viewport — `document.documentElement.scrollWidth <= clientWidth`, and every CTA `boundingBox()` has height >= 44 and width >= 44."
  - "[happy] AC-9 (rider) — Keycloak chrome honours `?ui_locales=fr`. Assertion: `login.spec.ts` locale test loses `@quarantine-kc26` and asserts `html` has attribute `lang=fr`."
  - "[happy] AC-10 (rider) — The SPA stays signed in past access-token expiry. Assertion: `token-lifecycle.spec.ts` silent-refresh test loses `@quarantine-kc26` and asserts the host is not the Keycloak host and `landing-topbar-sign-in` has count 0."
  - "[key-error] AC-11 (rider) — CI rejects a realm-export password outside the allow-set. Assertion: a negative test feeds `check-realm-shape.sh` a realm file that carries a foreign password and asserts a non-zero exit code."
screen: /lostpassword + /confirm   # replacing legacy flsweb/src/lostpassword/ + flsweb/src/confirm/
headless_pulled_in: none — Keycloak owns every credential action; this journey adds NO app write endpoint
migration: N/A — greenfield. Legacy credentials never migrate (ADR 0007 forbids password_hash on t_user).
parity_test: alpenflight/web/e2e/tests/real-idp/account-recovery.spec.ts (+ legacy pair e2e/tests/auth/lostpassword-parity-J19.spec.ts for the gallery)
adr_refs: [0007, 0013, 0017, 0024, 0026]
---

## Context

A member who forgets the password has no route today. The landing page offers only "Sign in", and
Keycloak's reset flow is reachable only if the member already knows the Keycloak URL. This journey
adds the two AlpenFlight pages that start and end the Keycloak credential flows, and it proves the
full reset chain against a real Keycloak and a real mail server. It closes the E-12 entry epic.

## Spec must assert

**The reset chain (the load-bearing proof).** `/lostpassword` hands off to Keycloak. Keycloak sends
the reset mail. Mailpit receives exactly one message. The link sets a new password. The new password
signs the user in. The old password fails with a Keycloak inline error. A second use of the link
fails and returns the user to `/lostpassword`.

**The confirmation page.** `/confirm` renders the outcome of a Keycloak email action: verified, or
link expired / already used. Each state shows one action: sign in, or start the recovery again.

**Public + mobile.** Both routes render without a session. Both fit 360 x 640 portrait. Every CTA is
at least 44 x 44 px (S-100 AC-DIR-1 / AC-DIR-2, ADR 0017).

**Parity posture — deliberate divergence, not a parity claim.** Legacy generated a new password and
mailed it (`flsweb/src/lostpassword/LostPasswordController.js:13`), and legacy `/confirm` collected
the new password in the app (`flsweb/src/confirm/ConfirmEmailController.js:24`). AlpenFlight mails a
reset LINK and Keycloak collects the password. The legacy pair spec captures the legacy screens for
the side-by-side gallery only. Do NOT assert behavioral parity. File this as an ADR 0026 entry —
`/do-ship` does not edit ADRs, so raise it to the operator.

## Notes

**Homing decision — no app endpoint.** `/lostpassword` does not post an address to AlpenFlight. It
explains the step and hands off, the same shape `signup.component.ts` uses (`oidc.authorize` with
`ui_locales`). This keeps credentials inside Keycloak (ADR 0007) and avoids a second unauthenticated
write endpoint with its user-enumeration and abuse surface.

**Ephemeral principal is mandatory.** The reset test MUST create and delete its own Keycloak user
(`keycloak-admin.createUser` + `freshTestUser()`, the `token-lifecycle.spec.ts` pattern). Resetting
`pilot1@example.com` would change the password that every other real-idp spec signs in with.

**Known red on the critical path.** `register.spec.ts`'s Mailpit verify-mail is `@quarantine-kc26` —
the mail never arrives after the Keycloak 26 upgrade. AC-6 depends on that red turning green, so
J-19 owns the KC-26 SMTP investigation. T-30d already added a fail-loud SMTP preflight; read its
output first. Realm SMTP is configured (`realm-export.json:2817`) and `verifyEmail` is on (`:3841`).

**Keycloak surfaces already in place.** `resetPasswordAllowed: true` (`:2382`), the
`alpenflight` login theme overrides `footer.ftl` + four message bundles, and the `alpenflight-web`
client accepts `http://localhost:4200/*` and `:4201/*`, so both new routes are valid redirect
targets without a realm change.

**Open behavior — verify at build time, do not block on it.** Keycloak's own info and error pages
end the failed and session-less flows. AC-4 and AC-5 need the theme back link to target `/confirm`
or `/lostpassword`. The theme already carries a custom `backToLanding` key, so the override is
in-pattern. If Keycloak 26 does not expose the target, fall back to the client `baseUrl` and record
the fallback in the journey Outcome.

**Task seams (non-binding).** `/lostpassword` page + route · `/confirm` page + route (outcome
states) · login-theme back links + the four message bundles · `account-recovery.spec.ts` ·
`public-routes.spec.ts` extension · the three `@quarantine-kc26` un-quarantines ·
`check-realm-shape.sh` allow-set gate + its negative test ·
`KeycloakDeploymentDirectoryAdapter.setUserAttribute` · `inline-validation.ts` first-paint gate ·
legacy pair spec · gallery captions with the `journey:` tag.

**No design reference exists for these two screens.** `design-reference/screens-public.jsx` covers
Landing and Login only; its Login card carries a "Forgot password" link (`:212`) that informs the
Keycloak login theme, not these pages. Build both pages from the J-17 public form primitive
(`public-form-shell.component.ts`) and ADR 0024, and say so in the PR.

## Tasks

- [x] **T-01** — MAIN-1 fix (`5d709fe0d`): the two DCT seed dates are relative to the run date. Offset 30 days inside the free range `[4, 89]`; worst-case margin 29 days, swept over 3000 run dates plus clock and zone skew.
- [x] **T-02** — Scaffold: `account-recovery.spec.ts` stub (all `test.fixme`, real selectors + flow) + the J-19 proof-gallery page linked from the index.
- [x] **T-03** — MAIN-1 guard: T-01's constant + `daysAgo()` now live in `tests/real-idp/_helpers/seed-flight-date.ts`; all three real-idp seed sites derive the date from the run date. `scripts/absolute-flight-date-in-api-seed-guard.mjs` rejects an absolute `flightDate` / `startDateTime` / `ldgDateTime` inside an API POST across `e2e/tests/**`, and runs in `ci.yml`'s `changes` job on every push with no path filter. The 13 remaining mock-lane dates sit in `route.fulfill` response bodies, which no server window can expire; they stay with the suite-wide date audit.
- [ ] **T-04** — MAIN-2 fix: `nightly.yml` legacy server build restores every `packages.config` and asserts the assemblies exist at the restore step.
- [ ] **T-05** — MAIN-3 fix: `nightly.yml` legacy web build sets `PHANTOMJS_SKIP_DOWNLOAD=true`.
- [ ] **T-06** — `/lostpassword` page + route: public, branded, hands off to Keycloak.
- [ ] **T-07** — `/confirm` page + route: verified / expired outcome states, one action each.
- [ ] **T-08** — Keycloak login theme: back-link targets for the info and error pages + the four message bundles.
- [ ] **T-09** — Rider `[PHANTOM-PASSWORD-GUARD]`: the realm-password allow-set gate in `check-realm-shape.sh` + its negative test.
- [ ] **T-10** — Rider `[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]`: read-merge-write in `KeycloakDeploymentDirectoryAdapter.setUserAttribute`.
- [ ] **T-11** — Rider KC-26: the Mailpit verify-mail red — un-quarantine the `register.spec.ts` happy path (AC-6).
- [ ] **T-12** — Rider KC-26: `?ui_locales=fr` — un-quarantine the `login.spec.ts` locale test (AC-9).
- [ ] **T-13** — Rider KC-26: silent refresh — un-quarantine the `token-lifecycle.spec.ts` test (AC-10).
- [ ] **T-14** — Legacy pair spec `e2e/tests/auth/lostpassword-parity-J19.spec.ts` + the paired gallery captures.
- [ ] **T-15** — Thicken `account-recovery.spec.ts` to the full assertions for AC-1 to AC-5, AC-7 and AC-8.

## Main-branch reds — fix these FIRST

`main` is red on three scheduled lanes at sha `62a8d3c5b`. The per-push `ci` lane is green, so no
merge is blocked. The reds sit in the lanes that nobody reads, and the fan-out has been red for at
least 8 days without notice. **J-19 cannot reach a green gate until MAIN-1 is fixed**, because the
J-19 gate runs the same real-idp suite.

### MAIN-1 — `alpenflight e2e real-idp` + `alpenflight proof fan-out`: an expired date in a fixture

**Root cause — proven, not suspected.** `delivery-creation-test-parity.spec.ts:127` and `:659` seed
the flight at the absolute date `'2026-05-15'`. The DCT flight picker calls
`flights.list()` with no arguments (`delivery-creation-tests.store.ts:299`), and
`FlightsService.java:47` applies `DEFAULT_WINDOW_DAYS = 90`. The window reached the seed date
between the two runs:

| Nightly run | Window starts | Seed 2026-05-15 in window | Result |
| --- | --- | --- | --- |
| 2026-08-13 | 2026-05-15 | yes | green |
| 2026-08-14 | 2026-05-16 | no | red |

The sha did not change between those two runs (`cc59242e7` for both). The picker renders only its
placeholder, so `selectOption(scenario.flightId)` waits for an option that does not exist and the
test times out at 45 s. Both failing tests share this cause, in both lanes.

**Fix.** Make the seed date relative to the run date, and keep it inside the 90-day window while it
stays past the lock and bill gates (`FlightGatePolicy.LOCK_AFTER_DAYS` / `BILL_AFTER_DAYS`).

**Guard — shipped at T-03, and the blast radius was smaller than the carve estimated.** Only an
API-SEEDED date can expire against the list window. There were exactly three such sites, all now on
the shared helper `e2e/tests/real-idp/_helpers/seed-flight-date.ts`. The other 13 absolute dates
were measured, not assumed: each of those specs has **zero `.post()` calls**, so every one of those
dates sits in a `route.fulfill` response body that no server window can expire. The carve's claim
that `05-flights-edit` would red on 2026-08-18 was wrong. The guard
(`web/scripts/absolute-flight-date-in-api-seed-guard.mjs`, wired at `ci.yml:104` in the graph-root
`changes` job with no `if:`/`needs:`) walks all of `e2e/tests/**`, so it covers its own inputs and a
future API seed at an absolute date reds at once.

### MAIN-2 — `nightly` / legacy server build: the restore step does not cover its own inputs

**Root cause — proven.** The restore step loops over two projects only —
`FLS.Server.Web/FLS.Server.WebApi.csproj` and `FLS.Server.Console/FLS.Server.Console.csproj`.
`FLS.Common/packages.config` declares `EnterpriseLibrary.Common`, `EnterpriseLibrary.Validation` and
`System.Linq.Dynamic`, and `FLS.Data.WebApi/packages.config` declares the first two. Neither file is
ever restored. The build worked only while the `actions/cache` entry carried those packages from an
older run. The 2026-08-16 log reads `Cache not found for input keys: nuget-Linux-…`, the restore
still exited 0, and `xbuild` then failed with 14 `CS0234` / `CS0246` errors in `FLS.Common`. The
restore reports success while it restores an incomplete set — the same shape as
[[project_gate_must_cover_its_own_inputs]].

**Fix.** Restore the solution (`nuget restore FLS.sln`) or loop over every `packages.config`, then
assert the expected assemblies exist under `flsserver/src/packages/` and fail at the restore step,
not two steps later. Keep the change in `.github/workflows/nightly.yml`; `flsserver/` is read-only.

### MAIN-3 — `nightly` / legacy web build: the phantomjs postinstall fails

**Root cause — proven.** `yarn install` reaches `[4/4] Building fresh packages...` and fails on
`node_modules/phantomjs`: `PhantomJS not found on PATH` then
`TypeError: Path must be a string. Received undefined` at `install.js:127` in
`findSuitableTempDirectory`. The postinstall runs only on a cache miss, so the same eviction that
exposed MAIN-2 exposed this. PhantomJS is dead upstream and its download host is gone.

**Fix.** Set `PHANTOMJS_SKIP_DOWNLOAD=true` on that install step in the workflow. The nightly builds
and serves `flsweb` for Playwright and never runs the legacy Karma suite, so the binary is not
needed. Keep the change in the workflow; do not edit `flsweb/`.

### MAIN-4 — the reds were invisible for 8 days

All three lanes are scheduled, and a scheduled red gates no PR. The fan-out failed on 8 consecutive
days and the operator learned it from this carve, not from the lane. J-30 gave the nightly loud
surfacing; the fan-out did not get it. Add the same treatment to the fan-out, or fold the fan-out
verdict into the surface the operator already reads.

**Verify cold, not warm.** MAIN-2 and MAIN-3 both hid behind a warm cache for months. Whatever fixes
them must be proved on a cache miss, otherwise the next eviction re-opens both.

## Riders folded in (from `_BOYSCOUT.md`, highest severity first)

| Rider | Sev | Why it rides J-19 |
| --- | --- | --- |
| **[PHANTOM-PASSWORD-GUARD]** | S1 | A claimed CI guard over realm passwords does not exist. Same `alpenflight/auth/scripts/` surface. AC-11. |
| **[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]** | S1 | `setUserAttribute` sends an attributes-only PUT that Keycloak treats as a full replace. Same Keycloak-admin surface. |
| **[KC-26 UPGRADE DRIFT]** | S2 | All three quarantined tests are this journey's surface: verify-mail (AC-6), `ui_locales` (AC-9), silent refresh (AC-10). |
| **[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]** | S2 | Red since J-13. One missing Flyway placeholder. Fix it so the lane is honest. |
| **[REQUEST-ID-NEVER-LOGGED]** | S2 | MDC key `requestId` against `%X{request_id:-}`. Request tracing has never worked. One-line fix. |
| **[FORM-FIRST-PAINT-RED]** | S2 | Fix in `inline-validation.ts`, not per form. Fold only if `/lostpassword` carries a field; otherwise defer and say why. |
| **[FIELDSET-LEGEND-SIZE]** | S3 | Same J-17 public form components these pages reuse. |

**The debt slot is now over-subscribed. MAIN-1 to MAIN-4 take priority over this table**, because a
red main lane costs more than any rider and MAIN-1 blocks this journey's own gate. Burn down in this
order: MAIN-1, MAIN-2, MAIN-3, then `[PHANTOM-PASSWORD-GUARD]` and
`[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]` (both S1 and both on the auth surface), then the KC-26 trio
that AC-6, AC-9 and AC-10 name. **Drop from J-19 if the budget runs out:**
`[FORM-FIRST-PAINT-RED]`, `[FIELDSET-LEGEND-SIZE]`, `[REQUEST-ID-NEVER-LOGGED]` and
`[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]`. Return each dropped rider to `_BOYSCOUT.md` and say so in
the PR.

**Explicitly NOT riding J-19 — [ANON-WRITE-ATTRIBUTION]** [S1]. The operator adjudicated it on
2026-08-14 and it is now a small feature: a new `actor_kind`, a `client_ip` column, a 90-day
retention job, and a privacy-notice entry. Its surface is J-17's public-registration write path,
which J-19 does not touch, and it does not fit the 40 % budget beside the KC-26 work. It needs its
own carve or the next journey over the public write path. Raise it to the operator.

## Assumptions made

1. **`next` resolves to J-19.** Its only dependency, J-16, shipped as #244. J-20 and J-21 are larger
   and sit later in the roadmap order.
2. **Keycloak's reset-credentials endpoint is not deep-linked.** `/lostpassword` hands off through
   the standard authorize call, because a direct `login-actions/reset-credentials` link needs a
   Keycloak authentication session. If the direct link proves stable at build time, use it.
3. **The used-link case supplies the expired state.** Using the reset link twice is deterministic and
   needs no realm patch, so AC-4 does not depend on an action-token lifespan override.
4. **The 60/40 split holds, but the debt slot is full.** The two pages plus the reset chain are the
   feature. MAIN-1 to MAIN-4 plus the two S1 auth riders fill the tech-debt slot. The list is
   ordered so `/do-ship` stops at the budget and returns the rest to `_BOYSCOUT.md`.
5. **The main-branch reds ride J-19; they do not get their own journey.** MAIN-1 blocks the J-19
   gate, so it must be fixed inside this journey. MAIN-2 to MAIN-4 are bounded workflow fixes, and
   the standing rule sends bounded work to the next journey's gate
   ([[feedback_no_tiny_stories_fix_forward]]). If MAIN-1's guard grows into a suite-wide date
   sweep, ship the two failing seeds plus the guard, and file the sweep as a rider.
