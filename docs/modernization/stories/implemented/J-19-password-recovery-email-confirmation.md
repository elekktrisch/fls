---
id: J-19
title: Password recovery + email confirmation pages
epic: E-12
status: done
started_at: 2026-08-16
done_at: 2026-08-19
journey0: false
hardening: false
carved: true
depends_on: [J-16]
rolls_up: [S-100]
acceptance:
  - "[happy] AC-1 — The landing page links to `/lostpassword`. The page renders without a session, and its primary action moves the browser to a Keycloak URL. Assertion: `account-recovery.spec.ts` — goto `/`, click `landing-topbar-lost-password`, land on `/lostpassword` with no `page.goto`, then click `lostpassword-start` and `waitForURL(/\\/realms\\/alpenflight\\//)`."
  - "[happy] AC-2 — An ephemeral user completes the reset chain and signs in with the NEW password. Assertion: same spec — create the user through `keycloak-admin.createUser`, `waitForExactlyOneMessage` in Mailpit, follow the link, set the new password, count the accepted authorization-code grant, and assert the tenant guard lands the club-less member on a visible `join-page`."
  - "[key-error] AC-3 — The OLD password stops working after the reset. Assertion: same spec — the URL stays on `/realms/alpenflight/login-actions/authenticate` and `KC_ERROR_SELECTOR` is visible."
  - "[key-error] AC-4 — A second use of the same reset link does not authenticate the user. Keycloak serves the spent link with HTTP 400 and returns the member to `/lostpassword`. Assertion: same spec — follow the theme back link, then assert the pathname and `lostpassword-page`."
  - "[happy] AC-5 — A verify-email link opened in a session-less browser lands on `/confirm` in the verified state. Assertion: same spec — open the Mailpit link in a fresh `browser.newContext()`, follow the theme back link, assert `confirm-outcome-verified` and `confirm-sign-in`."
  - "[happy] AC-6 — A new member registers through the migrate CTA, the real Keycloak SMTP path delivers the verify mail, and the handshake page renders. Assertion: `register.spec.ts` happy path without `@quarantine-kc26` — open `/signup?intent=migrate`, follow the Mailpit verify link, assert `toHaveURL(/\\/migrate\\/start$/)` and `migrate-handshake`. **Qualified:** the page renders and the handshake does not complete. `POST /api/v1/migrations/handshake` answers 403 to a club-less registrant, so the page shows its error state. The spec declares that 403 as a known product defect. Rider `[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` holds the fix, and J-21 owns it."
  - "[edge] AC-7 — Both new routes are public. Assertion: `public-routes.spec.ts` — an unauthenticated goto of `/lostpassword` and of `/confirm` renders each page testid, and the URL never enters `/realms/`."
  - "[edge] AC-8 — Both pages fit a 360 x 640 portrait viewport, and every call-to-action button meets the touch-target rule. Assertion: same spec at that viewport — `scrollWidth <= clientWidth`; the `af-button` count equals the measured testid list, so an unmeasured call to action reds; each measured `boundingBox()` has height >= 44 and width >= 44. The measured set is `lostpassword-start` + `lostpassword-sign-in-link` + `confirm-sign-in`. The header locale switches are chrome, not calls to action."
  - "[happy] AC-9 (rider) — Keycloak chrome honours `?ui_locales=fr`. Assertion: `login.spec.ts` locale test without `@quarantine-kc26` asserts `html` has attribute `lang=fr`."
  - "[happy] AC-10 (rider) — The SPA stays signed in past access-token expiry. Assertion: `token-lifecycle.spec.ts` silent-refresh test without `@quarantine-kc26` — Keycloak ACCEPTED at least one `refresh_token` grant (status-aware count), and the page stays on `/flights`."
  - "[key-error] AC-11 (rider) — CI rejects a realm-export password outside the allow-set. Assertion: `check-realm-shape-rejects-planted-drift.sh` feeds `check-realm-shape.sh` a planted realm file and asserts a non-zero exit code."
screen: /lostpassword + /confirm (replaces legacy flsweb/src/lostpassword/ + flsweb/src/confirm/)
headless_pulled_in: none — Keycloak owns every credential action; this journey adds NO app write endpoint
migration: N/A — greenfield. Legacy credentials never migrate (ADR 0007 forbids password_hash on t_user).
parity_test: alpenflight/web/e2e/tests/real-idp/account-recovery.spec.ts
adr_refs: [0007, 0013, 0017, 0024]
---

## Context

A member who forgot the password had no route. The landing page offered only "Sign in", and the
Keycloak reset flow was reachable only through a known Keycloak URL. This journey adds the two
AlpenFlight pages that start and end the Keycloak credential flows, links the recovery page beside
"Sign in", and proves the full reset chain against a real Keycloak and a real mail server. It closes
the E-12 entry epic.

## Contract — what the proof asserts

**The reset chain.** `/lostpassword` hands off to Keycloak. Keycloak sends the reset mail, and Mailpit
receives exactly one message. The link sets a new password, and that password signs the user in. The
old password fails with a Keycloak inline error. A second use of the link fails with HTTP 400 and
returns the member to `/lostpassword`.

**The confirmation page.** `/confirm` renders the verified outcome of a Keycloak email action, and it
shows one action: sign in. A spent or expired action never reaches `/confirm`; the Keycloak page
returns the member to `/lostpassword`, which AC-4 asserts.

**Public + mobile.** Both routes render without a session, fit 360 x 640 portrait, and give every call
to action at least 44 x 44 px (S-100 AC-DIR-1 / AC-DIR-2, ADR 0017).

## Parity posture — no exclusion, and no ADR 0026 entry

The carve claimed that legacy generated a new password and mailed it. That claim is wrong, and the
carve read it from the button label `GENERATE_NEW_PASSWORD`. `UsersController.cs:419-433` generates a
reset TOKEN, builds a callback URL, and mails that LINK — the same shape AlpenFlight uses.

The only difference is WHERE the member types the password. Legacy collected it in the application
(`ConfirmEmailController.js:24`); Keycloak now collects it on its own page. From the member's view both
flows are identical: ask, receive a mail, follow a link, set a password. This is an ownership change
that ADR 0007 already decided, so it needs NO ADR 0026 entry. The legacy pair spec captures the old
screens for the gallery only, and it asserts no behavioural parity.

## Decisions (load-bearing)

1. **Keycloak owns credentials (ADR 0007), so the journey adds NO backend write endpoint.**
   `/lostpassword` posts no address to AlpenFlight. It explains the step and calls `oidc.authorize` with
   `ui_locales`, the shape `signup.component.ts` uses. This avoids a second unauthenticated write
   endpoint with its user-enumeration and abuse surface.
2. **`/confirm` renders the verified outcome only.** Three producers prove that no Keycloak page sends
   a member to `/confirm` in a failed state: `footer.ftl` gives only three back-link targets,
   `KeycloakAdminClient.java:313` sends `execute-actions-email` with no `redirect_uri`, and
   `auth.config.ts:13` registers `/auth/callback` as the single OIDC target. T-18 deleted the expired
   state and its whole parser, and AC-4 asserts the `/lostpassword` return instead.
3. **The `[KC-26 UPGRADE DRIFT]` rider was disproven 3/3.** No quarantined test failed on Keycloak 26.
   The register test never filled the required `#username` field; the locale test sent no PKCE parameters,
   which the `alpenflight-web` client requires; the silent-refresh test set a token lifespan shorter than
   the 60 s SPA renew window. All three were our own defects. The third exposed a production bug: the
   session bridge ran the post-login redirect on every silent renew, so each rotation moved the operator
   to `/start` every 14 minutes (`oidc-session-bridge.ts:52`).
4. **Every image build bakes `http://localhost:4201/`.** Port 4200 serves the mock-auth SPA, which never
   contacts Keycloak; 4201 serves the SPA that follows a link Keycloak renders. A 4200 `baseUrl` sent the
   theme back link to the wrong application, so AC-4 and AC-5 would have proved nothing.
5. **The reset test creates and deletes its own Keycloak user.** Resetting `pilot1@example.com` would
   change the password every other real-idp spec signs in with.
6. **A guard covers its own inputs.** This journey found that defect three times: the date guard missed
   `_helpers` and the root suite (T-17), it missed the backtick quote its own message recommended (T-21),
   and `compose-lint.yml` filtered the bring-up guard away from nine of its eleven sites (T-22). Every
   guard now runs in `ci.yml`'s graph-root `changes` job, which carries no `paths:`, `if:` or `needs:`.
7. **gh-pages retention deletes only what no surviving page reaches.** The site reached 913.7 MB against
   the 1 GB cap, so a Pages BUILD errored while the git push succeeded. Reachability, not the producer
   workflow, decides each deletion, and the rule re-parses the SURVIVING pages until the set is stable.

## Tasks (shipped)

- [x] **T-01** — Seed the DCT parity flights relative to the run date (MAIN-1 fix).
- [x] **T-02** — `account-recovery.spec.ts` stub + the J-19 proof-gallery page.
- [x] **T-03** — `absolute-flight-date-in-api-seed-guard.mjs` + the shared `seed-flight-date.ts` helper.
- [x] **T-04 / T-04b** — The legacy NuGet restore covers every `packages.config`, asserts the assemblies, and retries three times (MAIN-2).
- [x] **T-05** — `npm_config_tmp` for the legacy web build's phantomjs postinstall (MAIN-3).
- [x] **T-06** — `/lostpassword` page + route: public, branded, hands off to Keycloak.
- [x] **T-07** — `/confirm` page + route.
- [x] **T-08** — `footer.ftl` picks the back-link target from the message the Keycloak page shows.
- [x] **T-08b** — No work needed; the real-idp job rebuilds the Keycloak image uncached.
- [x] **T-09** — Rider `[PHANTOM-PASSWORD-GUARD]`: the realm-password allow-set gate + its negative test.
- [x] **T-10** — Rider `[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]`: read-merge-write, with a proving IT.
- [x] **T-11 / T-11b / T-11c** — KC-26 verify mail (AC-6): fill `#username`, drive `intent=migrate`, assert `migrate-handshake`, file the 403 defect.
- [x] **T-12** — KC-26 `?ui_locales=fr` (AC-9): send the PKCE parameters the web client requires.
- [x] **T-13 / T-13b** — KC-26 silent refresh (AC-10): a 90 s lifespan, the redirect-on-renew fix, and a real hard-401 rotation test.
- [x] **T-14** — Legacy pair spec `lostpassword-parity-J19.spec.ts` + the gallery captures.
- [x] **T-15** — `account-recovery.spec.ts` at 9 active cases, green against live Keycloak 26.5.7.
- [x] **T-16** — The landing topbar links to `/lostpassword`, and AC-1 clicks through it.
- [x] **T-17** — The date guard reads every `.ts` file under both e2e trees and both POST forms.
- [x] **T-18** — Delete `/confirm`'s unreachable expired branch and its parser.
- [x] **T-19** — Every bring-up site bakes 4201, with two guards.
- [x] **T-20** — Four assertions that could pass for the wrong reason: endpoint-bound console allowances, status-aware grant counters, read-merge-write in the test helpers, and `resetPasswordAllowed` in the realm guard.
- [x] **T-21** — The date guard reads all three quote styles, `.put(` / `.patch(`, and a hoisted request body.
- [x] **T-22** — The bring-up guard moves to the unfilterable lane and reads its own wiring; AC-8 measures both calls to action.
- [x] **T-23** — Self-host Roboto in the login theme + a guard that rejects any external theme resource.
- [x] **T-24 / T-25 / T-26** — gh-pages retention: the true Pages-build cause, a payload-size guard at 400 MB, a measurement of the published payload only, and reachability as the rule for every deletion (914.6 MB → 206.9 MB, zero dead links).

## Outcome

Shipped in **#251**. The gate is green on `52e0fe30e`: `ci`, `alpenflight e2e real-idp`,
`compose-smoke`, `compose-lint` and `qodana` all succeeded. `account-recovery.spec.ts` runs 9 cases and
proves AC-1 to AC-5, AC-7 and AC-8 against a real Keycloak, a real Mailpit and the SPA on 4201. AC-6 is
qualified above. Migration is N/A, so no fan-out parity applies.

Three main-branch reds are fixed: the expired fixture date (MAIN-1), the incomplete legacy NuGet restore
(MAIN-2), and the phantomjs temp directory (MAIN-3). Nightly run `31932586109` proves MAIN-2 and MAIN-3
on a cold cache. MAIN-4 (a scheduled red gates no PR) stays open for the fan-out.

Riders shipped: `[PHANTOM-PASSWORD-GUARD]`, `[KC-SET-USER-ATTRIBUTE-PARTIAL-PUT]`, and
`[KC-26 UPGRADE DRIFT]` (discharged 3/3). The full debt budget returned four riders to `_BOYSCOUT.md`:
`[MAPPER-VS-SCHEMA-TEST-RED-SINCE-J-13]`, `[REQUEST-ID-NEVER-LOGGED]`, `[FORM-FIRST-PAINT-RED]`,
`[FIELDSET-LEGEND-SIZE]`. Escalations filed:
`[MIGRATE-HANDSHAKE-403-FOR-CLUBLESS-REGISTRANT]` [S1] to J-21, `[WEB-SCRIPTS-NOT-TYPECHECKED]` [S2],
`[QODANA-BUILD-FILE-BLIND-SPOT]` [S3], `[KC-ACCOUNT-CONSOLE-FRAME-SRC]` [S3], and
`[PREVIEWS-INDEX-STALE]` [S3]. The manager runs `gh-pages-retention.yml` after the merge.
