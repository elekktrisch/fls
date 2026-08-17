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
  - "[happy] AC-1 — The landing page links to `/lostpassword`, the page renders without a session, and its primary action moves the browser to a Keycloak URL. Assertion: `account-recovery.spec.ts` — goto `/`, click `landing-topbar-lost-password`, land on `/lostpassword` with no `page.goto`, `lostpassword-page` visible, click `lostpassword-start`, `waitForURL(/\\/realms\\/alpenflight\\//)`."
  - "[happy] AC-2 — An ephemeral user completes the reset chain and signs in with the NEW password. Assertion: same spec — create the user through `keycloak-admin.createUser`, submit the address on the Keycloak reset form, `waitForExactlyOneMessage` in Mailpit, follow the link, set the new password, then sign in with it, wait for the tenant guard to land the club-less member on `/join`, and assert `join-page` is visible and `landing-topbar-sign-in` has count 0. **T-20 strengthened the post-sign-in assertion**: the count-0 check alone also passes on a blank application that fails every own-member read."
  - "[key-error] AC-3 — The OLD password stops working after the reset. Assertion: same spec — `fillKcLogin(ephemeral.email, oldPassword)` keeps the URL on `/realms/alpenflight/login-actions/authenticate` and `KC_ERROR_SELECTOR` is visible."
  - "[key-error] AC-4 — A second use of the same reset link does not authenticate the user, and the Keycloak page returns the user to `/lostpassword`. Assertion: same spec — visit the link again, follow the theme back link, assert the pathname is `/lostpassword` and `lostpassword-page` is visible."
  - "[happy] AC-5 — A verify-email link opened in a session-less browser lands on `/confirm` in the verified state with a sign-in action. Assertion: same spec — open the Mailpit verify link in a fresh `browser.newContext()`, follow the theme back link, assert `confirm-outcome-verified` visible and `confirm-sign-in` visible."
  - "[happy] AC-6 — A new member registers through the migrate CTA and the real Keycloak SMTP path delivers the verify mail. The member lands on `/migrate/start` and the handshake page renders. Assertion: `register.spec.ts` happy path runs without `@quarantine-kc26`, opens `/signup?intent=migrate`, follows the Mailpit verify link, then asserts `toHaveURL(/\\/migrate\\/start$/)` and `migrate-handshake` visible. **Qualified:** the page renders, and the handshake does not complete. `POST /api/v1/migrations/handshake` answers 403 to a club-less registrant, so the page shows its error state. The spec declares that 403 as a known product defect. Rider `[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` in `_BOYSCOUT.md` holds the fix, and J-21 owns it."
  - "[edge] AC-7 — Both new routes are public. Assertion: `public-routes.spec.ts` — an unauthenticated goto of `/lostpassword` and of `/confirm` renders each page testid and the URL never enters `/realms/`."
  - "[edge] AC-8 — Both pages fit a 360 x 640 portrait viewport and their actions meet the touch-target rule. Assertion: same spec at that viewport — `document.documentElement.scrollWidth <= clientWidth`, and every CTA `boundingBox()` has height >= 44 and width >= 44."
  - "[happy] AC-9 (rider) — Keycloak chrome honours `?ui_locales=fr`. Assertion: `login.spec.ts` locale test loses `@quarantine-kc26` and asserts `html` has attribute `lang=fr`."
  - "[happy] AC-10 (rider) — The SPA stays signed in past access-token expiry. Assertion: `token-lifecycle.spec.ts` silent-refresh test loses `@quarantine-kc26`, asserts Keycloak ACCEPTED at least one `refresh_token` grant, and asserts the host is not the Keycloak host and `landing-topbar-sign-in` has count 0. **T-20 made the counter status-aware**: a request-only count also rises on a rejected rotation that a silent re-authorize then repairs."
  - "[key-error] AC-11 (rider) — CI rejects a realm-export password outside the allow-set. Assertion: a negative test feeds `check-realm-shape.sh` a realm file that carries a foreign password and asserts a non-zero exit code."
screen: /lostpassword + /confirm   # replacing legacy flsweb/src/lostpassword/ + flsweb/src/confirm/
headless_pulled_in: none — Keycloak owns every credential action; this journey adds NO app write endpoint
migration: N/A — greenfield. Legacy credentials never migrate (ADR 0007 forbids password_hash on t_user).
parity_test: alpenflight/web/e2e/tests/real-idp/account-recovery.spec.ts (+ legacy pair e2e/tests/auth/lostpassword-parity-J19.spec.ts for the gallery)
adr_refs: [0007, 0013, 0017, 0024, 0026]
---

## Context

A member who forgot the password had no route. The landing page offered only "Sign in", and
Keycloak's reset flow was reachable only if the member already knew the Keycloak URL. This journey
adds the two AlpenFlight pages that start and end the Keycloak credential flows, links the recovery
page from the landing chrome beside "Sign in", and proves the full reset chain against a real
Keycloak and a real mail server. It closes the E-12 entry epic.

## Spec must assert

**The reset chain (the load-bearing proof).** `/lostpassword` hands off to Keycloak. Keycloak sends
the reset mail. Mailpit receives exactly one message. The link sets a new password. The new password
signs the user in. The old password fails with a Keycloak inline error. A second use of the link
fails and returns the user to `/lostpassword`.

**The confirmation page — CORRECTED at T-18.** The carve asked for two states on `/confirm`. Only
one state is reachable. `/confirm` renders the verified outcome of a Keycloak email action, and it
shows one action: sign in. A spent or expired action never reaches `/confirm`. The Keycloak page
returns the member to `/lostpassword`, which AC-4 asserts.

**Public + mobile.** Both routes render without a session. Both fit 360 x 640 portrait. Every CTA is
at least 44 x 44 px (S-100 AC-DIR-1 / AC-DIR-2, ADR 0017).

**Parity posture — CORRECTED at T-14. The divergence is narrower than the carve claimed.**
The carve said legacy generated a new password and mailed it. That is WRONG, and the carve inferred
it from the button label `GENERATE_NEW_PASSWORD`. `UsersController.cs:419-433` generates a password
reset TOKEN, builds a callback URL, and mails that LINK — the same shape AlpenFlight uses.

The real difference is only WHERE the user sets the password. Legacy collected it in the app
(`flsweb/src/confirm/ConfirmEmailController.js:24`). Keycloak now collects it on its own page. From
the member's view both flows are identical: ask, receive a mail, follow a link, set a password.

So this is an ownership change that ADR 0007 already decided, NOT a behavioural divergence. It does
NOT need an ADR 0026 entry. The legacy pair spec captures the old screens for the side-by-side
gallery only; assert no behavioural parity.

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
- [x] **T-03** — MAIN-1 guard: T-01's constant + `daysAgo()` now live in `tests/real-idp/_helpers/seed-flight-date.ts`; all three real-idp seed sites derive the date from the run date. `scripts/absolute-flight-date-in-api-seed-guard.mjs` rejects an absolute `flightDate` / `startDateTime` / `ldgDateTime` inside an API POST, and runs in `ci.yml`'s `changes` job on every push with no path filter. The 13 remaining mock-lane dates sit in `route.fulfill` response bodies, which no server window can expire; they stay with the suite-wide date audit. **T-03 shipped a narrower scan than this line first claimed — T-17 widened it.**
- [x] **T-04** — MAIN-2 fix: both legacy server builds now restore every `packages.config` under `flsserver/src` — the same glob the `actions/cache` key hashes — and a new step asserts the six HintPath assemblies exist before `xbuild` runs. `FLS.sln` was the wrong authority: it omits `FLS.Server.Console`, which the xbuild loop builds, and `FLS.Server.ProffixInvoiceService`, which the cache key hashes. `alpenflight-proof-fanout.yml` carried a solution restore with no assertion; both files now hold the identical two steps.
- [x] **T-04b** — T-04 restored every `packages.config`, which raised the nuget process count from 2 to 9, so one transient failure now reds the job far more often. The cold run failed on the 6th config with a Mono BoringSSL `CERTIFICATE_VERIFY_FAILED` against api.nuget.org. Each per-config restore now gets 3 attempts with a 10s then 20s backoff, plus `-NonInteractive`. A "package NuGet cannot find" output stops the retries at once, because no retry creates a package that does not exist. Both workflows hold the identical step; T-04's assertion step is unchanged.
- [x] **T-05** — MAIN-3 fix: `nightly.yml` legacy web build sets `npm_config_tmp` and asserts its own output.
- [x] **T-06** — `/lostpassword` page + route: public, branded, hands off to Keycloak. Assumption 2 holds:
  the page calls `oidc.authorize` with `ui_locales` and lands on the Keycloak login page, whose theme
  carries the forgot-password link. The mechanism sits in one function,
  `handOffToTheKeycloakLoginPageWhoseThemeCarriesTheForgotPasswordLink`
  (`alpenflight/web/src/app/features/lostpassword/keycloak-recovery-handoff.ts:7`); T-15 flips that
  function alone if a live run prefers the deep link. The page posts nothing to the backend.
- [x] **T-07** — `/confirm` page + route. The page built a verified state and an expired state, and
  it read the state from the query the Keycloak back link supplies. T-18 deleted the expired state
  and the query parser, because T-08 proved that no Keycloak page sends a member to `/confirm` in a
  failed state. The page now renders the verified state and one action: sign in. T-08 owns the
  back-link targets: `/confirm?outcome=verified` for the info page, `/lostpassword` for the spent
  reset (AC-4).
- [x] **T-08** — Keycloak login theme: `footer.ftl` reads the message the page shows and picks the
  back-link target from it. The "email verified" info page sends the member to
  `/confirm?outcome=verified` (AC-5), an "action expired" page sends the member to `/lostpassword`
  (AC-4), and every other page keeps the landing page. `info.ftl` and `error.ftl` need no override:
  both come from `base` and render our footer macro. All four bundles carry
  `backToEmailConfirmation` and `backToPasswordRecovery`. A client-less error page rendered HTTP 500
  before, because the macro read `client.baseUrl` with no guard; the macro now renders no link there.
  **Keycloak 26.5.7 puts a "confirm validity" page in front of the session-less verify link, so T-15
  must click `#kc-info-message a` first, then read the back link.**
- [x] **T-08b** — No work needed. `alpenflight-e2e-real-idp.yml:64-66` rebuilds the Keycloak image uncached inside the shard job on every run, and `alpenflight/auth/Dockerfile:48` copies `themes/`, so T-08's theme change is already live in the gate. T-15 confirms the two back-link targets in the real chain.
- [x] **T-09** — Rider `[PHANTOM-PASSWORD-GUARD]`: the realm-password allow-set gate in `check-realm-shape.sh` + its negative test.
  The gate extends `check-realm-shape.sh` (AC-11 names that script, and the export is already read there).
  It covers two classes of literal credential in the committed export: every `users[].credentials[].value`
  and every `clients[].secret`. It does NOT cover `smtpServer.password` or the Google IdP `clientSecret`:
  lines 131-134 and 146-147 already assert those are `${KEYCLOAK_...}` markers, which is a stronger rule
  than an allow-set. `check-realm-shape.sh` now takes an optional realm-file argument, so the negative test
  can feed it a planted file. The negative test is
  `alpenflight/auth/scripts/check-realm-shape-rejects-planted-drift.sh` (T-20 renamed it, because it now
  covers a realm flag too), wired into `ci.yml`'s graph-root `changes`
  job, which carries no `if:` and no `needs:` and therefore never gets path-filtered away.
- [x] **T-10** — Rider `[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]`: read-merge-write in
  `KeycloakDeploymentDirectoryAdapter.setUserAttribute`. The adapter now reads the full user
  representation, merges the one attribute, and re-sends every field a Keycloak PUT clears. The two
  Keycloak call sites share one idiom: the new shared-kernel record
  `ch.alpenflight.platform.keycloak.MergeableKeycloakUserRepresentation` holds the merge and the
  PUT body, and `KeycloakAdminClient.writeClubIdAttribute` / `clearClubIdAttribute` delegate to it.
  The proving test is
  `KeycloakDeploymentDirectoryAdapterAttributeMergeIT` (`alpenflight/server/src/test/java/ch/alpenflight/tenancy/provisioning/infra/KeycloakDeploymentDirectoryAdapterAttributeMergeIT.java:88`);
  it was red against the single-key PUT and is green against the merge.
- [x] **T-11** — Rider KC-26, register verify-mail (AC-6): never an SMTP fault. The realm keeps the
  username separate from the email (`alpenflight/auth/realm-export.json:2247`), so Keycloak's
  registration form renders a required `#username` field, and `kc-form.ts` never filled it. Keycloak
  rejected the form with "Please specify username", created no user, and sent no mail. The two
  negative register tests passed on that same error, so they proved nothing about the password policy
  or the duplicate address. `compose-smoke.yml:79` runs `check-keycloak-integration.sh`, which sends a
  real verify-email into Mailpit, and it is green on this branch — the mail layer was always healthy.
  The helper now fills every field the form requires, the happy path lost `@quarantine-kc26`, and the
  mail budget dropped to 20 s so it stays inside the 45 s real-idp test timeout. The nightly SMTP
  preflight now proves DELIVERY, not TCP reachability, so the next failure names its own layer.
- [x] **T-11b** — T-11's fix worked, and the real run then failed on a stale assertion. The happy path
  opened bare `/signup`, so `resolveSignupIntent(null)` returned `join`
  (`alpenflight/web/src/app/features/signup/signup-intent.ts:6`) and the browser landed on `/join`.
  The `@quarantine-kc26` tag had hidden that since the default flipped from `/migrate/start` to
  `/join`. The spec now opens the target of the landing migrate CTA
  (`alpenflight/web/src/app/features/landing/landing.component.ts:76`), `/signup?intent=migrate`,
  which `postSignupLandingPath` maps to `/migrate/start` (`signup-intent.ts:14`). `/migrate/start`
  carries only `authGuard` (`alpenflight/web/src/app/features/migrate-handshake/migrate-handshake.routes.ts:10`),
  so a club-less new member stays there. AC-6 named the pre-flip default; its wording now states the
  migrate-intent contract. The `/join` default keeps unit coverage
  (`alpenflight/web/src/app/features/signup/signup-intent.spec.ts:19`) and mock-auth coverage
  (`alpenflight/web/e2e/tests/public/signup.spec.ts:82`), but no real-idp spec registers through bare
  `/signup`.
- [x] **T-11c** — AC-6 narrowed to the render, and the product defect filed. T-11b's `intent=migrate`
  fix holds, because the URL assertion passed on the real run. The spec then failed on a stale testid:
  it asserted `migrate-start`, which `src/` never carried. The page renders `migrate-handshake`
  (`alpenflight/web/src/app/features/migrate-handshake/migrate-handshake.page.ts:37`), and the spec now
  asserts that testid. The spec declares two console errors by name. The `GET /handshake/current` 404
  is by design for a first-time registrant (`MigrationHandshakeController.java:52`). The
  `POST /handshake` 403 is a known product defect, so a `known-product-defect` annotation carries the
  rider tag into the Playwright report. Rider `[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` [S1]
  sits in `_BOYSCOUT.md` and belongs to J-21. The operator decided on 2026-08-16 that J-19 files the
  defect and does not fix the backend.
- [x] **T-12** — Rider KC-26, `?ui_locales=fr` (AC-9): never a Keycloak fault. Keycloak 26.5 honours
  `ui_locales` and renders `<html lang="fr">`, even when the browser sends `Accept-Language: en-US`.
  The spec built a raw authorize URL with no PKCE parameters, but the `alpenflight-web` client
  requires them (`alpenflight/auth/realm-export.json:1745`). Keycloak answered
  `302 → {redirect_uri}?error=invalid_request&error_description=Missing parameter:
  code_challenge_method`, so the browser landed on the SPA and never on the login page. The SPA reads
  `navigator.language` (`alpenflight/web/src/app/core/i18n/lang-providers.ts:19`) and writes it to
  `document.documentElement.lang` (`alpenflight/web/src/app/shared/ui/locale/locale.service.ts:49`) —
  that is where the `en` came from. The spec now sends `code_challenge` + `code_challenge_method`,
  asserts the page stays on the realm URL, and reads the French page title from our own bundle
  (`alpenflight/web/e2e/tests/real-idp/login.spec.ts:66`). The operator smoke
  `check-theme-load.sh` carried the same missing-PKCE fault plus a root-element pattern that the
  `keycloak.v2` parent never matches; it passes against a live 26.5 container again.
  `kc_locale` is inert on Keycloak 26.5 — only `ui_locales` selects the locale.
- [x] **T-13** — Rider KC-26, silent refresh (AC-10): never a Keycloak fault. Two of our own faults
  stacked. First, the test patched the realm `accessTokenLifespan` to 30 s, but the SPA renews 60 s
  before expiry (`alpenflight/web/src/app/core/auth/auth.config.ts:20`). Keycloak gives the id token
  the same lifespan, so `angular-auth-oidc-client` read the fresh id token as already expired at the
  code-flow callback, reset the tokens, and authorized again. A live Keycloak 26.5 run logged
  "authCallback id token expired" and looped between the SPA and the realm, so the SPA never held a
  session and nothing could renew. The spec now patches the lifespan to 90 s, above the renew window
  (`alpenflight/web/e2e/tests/real-idp/_helpers/keycloak-admin.ts:5`). Second, the library fires
  `NewAuthenticationResult` on every silent renew, and the bridge ran the post-login redirect for it,
  so each rotation moved the operator off the work page to `/start`. The live run showed the jump
  0.5 s after a `refresh_token` grant. In production that repeats every 14 minutes. The bridge now
  navigates only for a real sign-in
  (`alpenflight/web/src/app/core/auth/oidc-session-bridge.ts:52`). The spec counts the
  `refresh_token` grants and asserts the page stays on `/flights`, so it cannot pass without a real
  rotation. **The `hard 401` test in the same file still patches the 30 s lifespan, so it passes on
  the login loop, not on a hard 401 — it needs its own task.**
- [x] **T-13b** — Close the two escalations T-13 raised. (1) The `hard 401` test was a vacuous green.
  The SPA has no handler for a 401 response — `authInterceptor()` only attaches the bearer — so no
  API call can drive the redirect. The redirect comes from the denied token rotation: Keycloak
  rejects the `refresh_token` grant of a disabled user, and the bridge then clears the session and
  authorizes again (`alpenflight/web/src/app/core/auth/oidc-session-bridge.ts:96`). That needs the
  90 s lifespan, because the SPA renews 60 s before expiry and the canonical 900 s puts the rotation
  14 minutes away. The test now waits for the SPA to settle on a private route, holds that route
  through a dwell that exposes a login loop, disables the user, asserts Keycloak rejects the next
  `refresh_token` grant, and only then asserts the signed-out destination plus a new authorize
  request (`alpenflight/web/e2e/tests/real-idp/token-lifecycle.spec.ts:122`). The name states that
  mechanism. Live Keycloak 26.5 with the SPA on 4201: all four token-lifecycle tests green; red at
  the redirect assertion when `handleSilentRenewFailed` does not re-authorize; red at the
  private-route assertion when the lifespan returns to 30 s. (2) No spec carries `@quarantine-kc26`,
  so the `--grep-invert` token and its comment leave
  `.github/workflows/alpenflight-e2e-real-idp.yml:243`, and the discharged `[KC-26 UPGRADE DRIFT]`
  rider leaves `docs/modernization/stories/_BOYSCOUT.md`.
- [x] **T-14** — Legacy pair spec `e2e/tests/auth/lostpassword-parity-J19.spec.ts` + the paired gallery
  captures. The spec drives the legacy stack without a session and captures three screens:
  `/lostpassword`, the same screen after a real send, and the `/confirm` choose-password form. It
  asserts only that each screen renders, and it never sends the `/confirm` form. Sending that form
  calls `POST /api/v1/users/resetpassword` and would change the seeded password every other legacy
  spec signs in with. **No parity claim** — ADR 0007 gives every credential action to Keycloak, so
  the shots carry no AlpenFlight pair. The three PNGs and the video stage into the fan-out gallery;
  `alpenflight/web/e2e/legacy-reference/lostpassword/PENDING.md` holds the capture-once follow-up.
  **The legacy stack does not run on this box** (no Mono, no built `FLS.Server.Console`, no
  `/tmp/flsweb-build`, no MSSQL container), so the first live run is the fan-out job
  `alpenflight-proof-fanout.yml`. The `POST /api/v1/users/lostpassword` success path is already
  proven green there by `e2e/tests/email/notifications.spec.ts:143`.
- [x] **T-15** — `account-recovery.spec.ts` now carries 9 active cases and no `test.fixme`, so the
  per-push proof job runs THIS spec (`is_baseline=false`). AC-1, AC-2, AC-3, AC-4, AC-5, AC-7 and
  AC-8 all assert for real. Driven green LOCALLY against live Keycloak 26.5.7 + Mailpit + the SPA on
  4201: 9/9 in 46 s. Three findings came from the live run, not from reading.
  (1) **The theme back link targeted the wrong application in CI.** `docker-compose.yml:95` bakes the
  Keycloak client `baseUrl` from `ALPENFLIGHT_WEB_BASE_URL`, default `http://localhost:4200/` — the
  `pnpm start` port. The real-idp project serves the SPA on 4201, so AC-4 and AC-5 would have clicked
  through to the mock-auth instance and proved nothing about the application under test. The five
  Keycloak image builds that feed a real-idp run now pass `http://localhost:4201/`
  (`ci.yml` ×3, `alpenflight-e2e-real-idp.yml`, `alpenflight-proof-fanout.yml`), and the real-idp
  preflight gets the matching `EXPECTED_BASE_URL`. The spec asserts the back-link host equals the
  host under test. This fix reached CI only; T-19 carried it into the local loop and moved
  `compose-smoke.yml` to 4201 with it.
  (2) **T-08's back-link logic is correct.** A stale local image had hidden it. With the committed
  `footer.ftl` the spent reset link resolves to `/lostpassword` and the verified info page to
  `/confirm?outcome=verified`.
  (3) **Keycloak serves the spent action link with HTTP 400.** AC-4 now asserts that status instead of
  tolerating the console error it raises.
  The reset completes the Keycloak authentication, so AC-2 signs out through `/auth/logout` first and
  then proves the new password by counting the authorization-code grants Keycloak accepts. AC-7 also
  joins the `public-routes.spec.ts` table, which adds the no-bearer / no-nav-bar / no-undeclared-read
  assertions the journey spec does not make. Gallery: `ci.yml` carries the J-19 `add_pair` block for
  the `form` and `confirm` views, and the fan-out carries the two AlpenFlight `add_shot` halves plus
  the spec in its real-bundle list.
  **Not verified locally:** the backend does not run on this box, so the SPA answered from a stub. The
  post-sign-in SPA state of a club-less member is CI-only.

- [x] **T-16** — [BLOCKER, gap-hunter A] `/lostpassword` had no chrome entry point, so every proof
  entered by `page.goto`. The landing topbar now carries the link, directly before the "Sign in"
  button (`alpenflight/web/src/app/features/landing/landing.component.ts:40`). The topbar is the only
  place in the application where a session-less member starts a sign-in, and the design reference puts
  the recovery link next to the sign-in control
  (`docs/modernization/design-reference/screens-public.jsx:212`). The hero CTAs address a NEW member
  (migrate, demo, request access) and the footer holds legal links, so neither reaches the member who
  cannot sign in. `/signup` gets no second link: the landing page is the entry the member returns to.
  AC-1 now opens `/`, clicks `landing-topbar-lost-password`, and waits for the `/lostpassword`
  pathname before it asserts the page
  (`alpenflight/web/e2e/tests/real-idp/account-recovery.spec.ts:138`); no `page.goto` of
  `/lostpassword` remains in that case. The mock lane holds the same click plus the 44 x 44 pixel rule
  and a no-horizontal-scroll assertion at 360 x 640
  (`alpenflight/web/e2e/tests/landing/landing.spec.ts:136`), and four more cases prove the topbar
  still fits 360 px with each locale label (`:165`). AC-2 keeps its own `page.goto` entry, because
  that case proves the reset chain and not the entry point. The label is
  `landing.actions.lostPassword` in all four locale bundles. AC-1 ran green against the live
  Keycloak 26.5 container and the SPA on 4201; the Spring backend does not run on this box, and AC-1
  needs none.
- [x] **T-17** — [BLOCKER, both hunters] The absolute-date guard now reads **every `.ts` file under
  both `alpenflight/web/e2e/` and the repo-root `e2e/`** (145 files; `node_modules`, `test-results`,
  `playwright-report` and `screenshots` excluded), so `_helpers/*-fixture.ts` seeders and the whole
  root suite are inside it. It treats `.post(...)` **and** `.fetch(...)` as seeding call sites, and
  accepts a `.fetch` whose `method` is `'POST'` or is not a string literal that proves another verb;
  a literal `'GET'` and a method-less `.fetch` stay out. The field match accepts the root suite's
  legacy PascalCase spelling. `scanEveryGuardedTree` throws when a scanned tree moves, so the guard
  cannot silently cover nothing. **Nothing is exempt and no allow-list exists** — the widened scan is
  green over all 145 files. The two duplicate local `daysAgo()` copies in
  `_helpers/reporting-parity-fixture.ts` and `_helpers/daily-report-fixture.ts` now import the shared
  `_helpers/seed-flight-date.ts`. `absolute-flight-date-in-api-seed-guard.spec.ts` grew six cases
  that the T-03 guard scored 0 on: a `_helpers/*.ts` seed, a root-tree seed, an explicit
  `method: 'POST'` fetch, a variable-method fetch, the legacy PascalCase spelling, and the
  moved-tree throw — plus a GET/method-less green so the widening cannot over-fire. T-03's coverage
  claim is corrected here, in the MAIN-1 section, and in `_BOYSCOUT.md`.
- [x] **T-18** — [BLOCKER, gap-hunter B] `/confirm`'s expired branch was unreachable in production.
  Three producers prove it. `footer.ftl:12-21` gives a Keycloak page three back-link targets only:
  `/confirm?outcome=verified`, `/lostpassword`, and the landing page. `KeycloakAdminClient.java:313`
  sends `execute-actions-email` with no `client_id` and no `redirect_uri`, so Keycloak always renders
  its own page and never redirects the browser into the application. `auth.config.ts:13` registers
  `/auth/callback` as the single OIDC redirect target, so an OIDC `?error=` response cannot land on
  `/confirm`. The expired section, the `confirm-outcome-expired` and `confirm-restart-recovery`
  testids, the `keycloak-email-action-outcome.ts` parser with its unit spec, the two mock tests that
  drove the expired path, and three i18n keys in four locales are deleted. Removing the expired path
  left the parser with one possible result, so the whole parser is deleted, not simplified. The
  contract prose in "Spec must assert" and the T-07 record now state the reachable behaviour.
- [x] **T-19** — [BLOCKER, gap-hunter B] The 4201 base-URL fix was CI-only, so the documented local
  loop still baked 4200. The rule the code now states: 4200 serves the `mock-auth` SPA, which never
  contacts Keycloak; 4201 serves the `development` SPA, the only build that follows a link Keycloak
  renders. Therefore every image build bakes `http://localhost:4201/`. `Dockerfile:24` (the deepest
  default, missed by T-15), `docker-compose.yml:95`, `check-keycloak-integration.sh:9`,
  `check-theme-load.sh:9` and `rebuild-keycloak.sh:35` move to 4201; `dev-up-alpenflight.sh:10` and
  `rebuild-keycloak.sh:10` export the build arg instead of riding the compose default. `pnpm start`
  serves the real-OIDC SPA, so `package.json:10` pins it to 4201 and `package.json:11` pins
  `start:mock-auth` to 4200 — that also stops a running `pnpm start` from shadowing the mock-auth
  Playwright server. Two guards hold it: `test-bring-up-guards.sh` compares all eleven sites against
  the port `playwright.config.ts` serves the real-OIDC SPA on, and
  `check-keycloak-integration.sh:9-10` derives its expectation from `E2E_REAL_IDP_BASE_URL`, so the
  preflight compares the baked `baseUrl` with the SPA the suite is about to drive.
  `compose-smoke.yml` needs no env: both its build and its probe now default to 4201.
- [x] **T-20** — [SUSPECTS] Four assertions that could pass for the wrong reason, one seam: a guard that
  tolerates more than it names.
  (A) **The console allowances were status-only.** A `/\b403\b/` swallowed a 403 from any endpoint. The
  guard now records the failing resource URL beside the message
  (`alpenflight/web/e2e/tests/_helpers/console-guard.ts:69`), and
  `consoleErrorAllowanceForStatusesOnEndpoint` (`:44`) accepts an error only when the status matches AND
  the recorded resource pathname is exactly the declared endpoint. `register.spec.ts:26` binds the 404 to
  `/api/v1/migrations/handshake/current` and the 403 to `/api/v1/migrations/handshake`;
  `account-recovery.spec.ts:80` binds AC-2 to `/api/v1/me` and `/api/v1/me/join-request`. AC-2's own
  post-sign-in state is now `/join` plus a visible `join-page` (`:346`), not a sign-in button count of
  zero: the tenant guard reaches `/join` only after the backend answers the own-member reads
  (`alpenflight/web/src/app/core/session/tenant-required.guard.ts:32`), so an application that fails
  every `/api/v1/me` read renders nothing there — and a blank page scores zero on the old assertion too.
  `join-request.spec.ts:53` already proves that same landing for the same user shape in the same lane.
  (B) **AC-10 counted requests, not accepted rotations.** A rejected rotation plus a silent re-authorize
  on a live SSO cookie raised the count, kept the host off Keycloak and kept the CTA at zero. The three
  grant counters now live in `alpenflight/web/e2e/tests/_helpers/keycloak-grant-counters.ts` and all
  three read the response status. `keycloak-grant-counters.spec.ts:53` is the negative test: it drives a
  400 rotation and asserts the accepted count stays zero. It is red against a count that ignores the
  status.
  (C) **The `[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]` bug class was still live in test code.**
  `keycloak-admin.setUserEnabled` and `makeMigratedAdminLoginable` sent the same partial PUT T-10 fixed
  server-side, so Keycloak could clear the email and `sweepE2eUsers` could never reclaim the user
  (`isCleanupCandidate` filters on the email). Both call sites now read the full representation, merge
  the overrides and re-send it
  (`alpenflight/web/e2e/tests/real-idp/_helpers/keycloak-admin.ts:136`). `setUserEnabled` then re-reads
  the user and fails when the email moved, so the disabled-user case proves the merge on every run.
  (D) **The realm-shape guard never asserted `resetPasswordAllowed`.** That single flag carries AC-1 to
  AC-4. `check-realm-shape.sh:119` asserts it, and the negative test — renamed
  `check-realm-shape-rejects-planted-drift.sh`, because it covers more than credentials now — plants
  `resetPasswordAllowed: false`. The planted case exits 0 against the guard without the assertion, and
  exits 1 with it.
  **Verified locally:** D red then green; B red then green; A proven with a scratch Chromium run that
  showed the recorded resource URL, accepted the bound 403, and reddened an unrelated 403 that the old
  status-only pattern passed. `tsc -p e2e/tsconfig.json`, `ng lint` and `prettier --check` are green.
  **Not verified:** the real-idp chain, because the Spring backend does not run on this box.
- [x] **T-21** — [BLOCKER, gap-hunter pass 3] The guard could not catch the form its own message
  recommended. `absolute-flight-date-in-api-seed-guard.mjs:16` read `(['"])` only, so a
  backtick-quoted date was invisible, and `RULE_EXPLANATION:257` told the developer to build
  `startDateTime` / `ldgDateTime` with a template literal. MAIN-1 could therefore re-enter `main`
  through the guard's own advice. The value match now reads all three quote styles (`:16`). A
  template literal reds when its text starts with the date, and greens when it interpolates a derived
  date, so `` `${flightDate}T08:00:00Z` `` — the shape T-01 shipped — stays green and
  `` `2026-05-15T08:00:00Z` `` reds. The same match now also reads a quoted JSON-style key.
  `.put(`, `.patch(` and a `.fetch(` whose method is `'PUT'` or `'PATCH'` join the seeding verbs
  (`:125`, `:127`). A file that holds one seeding call site is guarded end to end (`:196`), so a
  request body hoisted to a const outside the call span reds; a file that never seeds reads no date
  at all, which keeps the 29 `route.fulfill` mock-lane dates green. `RULE_EXPLANATION:248` states
  every covered form and states the limit: the guard reads no data flow, so inside a file that seeds
  it cannot tell a hoisted seed body from a mocked response body, and it reds both.
  **Verified locally:** each new form scored 0 findings against the T-17 guard and ≥1 against the
  widened guard — backtick date, quoted key, hoisted const, `.put(`, `.patch(`, `PUT` `.fetch(`;
  the interpolated template literal and the mock-lane file scored 0 against both. Eight new cases in
  `absolute-flight-date-in-api-seed-guard.spec.ts` hold each form, and one case feeds the recommended
  template literal read out of `RULE_EXPLANATION` back through the guard, so the message can never
  again recommend a form the guard cannot verify. `pnpm test:scripts` is green at 53 tests. The
  widened scan over all 147 files stays green and caught nothing that was previously hidden, because
  every remaining absolute date sits in a file with zero seeding call sites.

## Gate obligations carried by later tasks

- **T-15 must click `#kc-info-message a`.** Keycloak 26.5.7 shows a "confirm validity" page before it
  acts on an email link, so the spec cannot follow the mail link straight to the outcome. T-08 proved
  this against a live 26.5.7 container.
- **Rebuild the Keycloak image before the gate.** T-08's theme change is inert until the image is
  rebuilt.
- **MAIN-2 and MAIN-3 are proven on a cold cache.** Nightly run `31932586109` on this branch logged
  `Cache not found for input keys: nuget-Linux-…`, downloaded `EnterpriseLibrary.Common` and
  `System.Linq.Dynamic` fresh, and went green on all three jobs, including the `e2e (Playwright)` job
  that the red build had been skipping. The earlier green run `31930362653` rode a warm cache and
  proved nothing.

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
that `05-flights-edit` would red on 2026-08-18 was wrong. The guard lives at
`web/scripts/absolute-flight-date-in-api-seed-guard.mjs` and is wired at `ci.yml:104` in the
graph-root `changes` job with no `if:`/`needs:`.

**T-03 did not cover its own inputs, and T-17 fixed that.** The T-03 scan collected only `*.spec.ts`,
rooted at `alpenflight/web/e2e/tests`, and recognised only a `.post(` call site. Three whole classes
of seed therefore passed green: a seed in a `_helpers/*.ts` fixture (nine such fixtures POST),
anything in the root `e2e/` suite, and a seed written as `request.fetch({ method: 'POST' })`. This is
the same defect class as MAIN-2 below. T-17 widened the scan to **every `.ts` file under both
`alpenflight/web/e2e/` and the repo-root `e2e/`** (`node_modules` and build output excluded), and to
**both POST forms** — `.post(...)`, plus `.fetch(...)` whose `method` is `'POST'` or is not a string
literal that proves it is another verb. The field match now also accepts the root suite's legacy
PascalCase spelling (`FlightDate` / `StartDateTime` / `LdgDateTime`). The guard throws when a scanned
tree moves, instead of silently covering nothing. Nothing is exempt: no allow-list exists, because
the widened scan found zero violations across all 145 scanned files.

**T-17 read only two of the three quote styles, and T-21 fixed that.** The value match accepted a
single-quoted and a double-quoted date only, so a backtick-quoted date stayed invisible — the exact
form the guard's own `RULE_EXPLANATION` told the developer to write. T-21 reads all three quote
styles, reds a template literal whose text starts with the date, and greens a template literal that
interpolates a derived date. It adds `.put(`, `.patch(` and a `PUT` / `PATCH` `.fetch(` to the
seeding verbs, and it guards a file end to end once that file holds one seeding call site, so a
request body hoisted to a const reds too. The message now states the limit it cannot see past.

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
exposed MAIN-2 exposed this. `install.js:121` reads `TMPDIR || TEMP || npm_config_tmp`. The runner
sets none of the three, so `path.join(undefined, 'phantomjs')` throws before the `/tmp` fallback.

**Fix.** Set `npm_config_tmp: /tmp` on that install step, as
`.github/workflows/alpenflight-proof-fanout.yml` already does. Two premises of the first analysis are
wrong, both disproven against the tarball that `flsweb/yarn.lock` pins:

- `phantomjs@1.9.20` does not read `PHANTOMJS_SKIP_DOWNLOAD`. That variable belongs to
  `phantomjs-prebuilt@2`. The setting is a no-op here and leaves `main` red.
- The download host is alive. `install.js:430` builds
  `https://github.com/Medium/phantomjs/releases/download/v1.9.19/phantomjs-1.9.8-linux-x86_64.tar.bz2`
  from `lib/phantomjs.js:31`. It returns 200, 13.2 MB, and the sha256 matches the checksum
  `install.js` verifies.

`phantomjs` is a transitive dependency of `highcharts@0.0.11`, which has no install script.
`flsweb/src/vendor/vendor.js:8` requires the browser bundle `highcharts/scripts/highcharts`, never
the `main` module that spawns the binary. The nightly builds and serves `flsweb` for Playwright and
never runs the legacy Karma suite, so the binary stays unused. Keep the change in the workflow; do
not edit `flsweb/`.

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
