---
id: J-19
title: Password recovery + email confirmation pages
epic: E-12
status: todo
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
4. **The 60/40 split holds.** The two pages plus the reset chain are the feature. The rider table is
   the tech-debt slot, and it is ordered so `/do-ship` can stop at the budget.
