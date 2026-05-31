---
id: S-134
title: Keycloak self-service signup + Google IdP federation
epic: E-15
status: done
started_at: 2026-05-27
done_at: 2026-05-27
merged: true
merged_at: 2026-05-27
depends_on: [S-019, S-021]
acceptance:
  - The Keycloak realm export (S-019) is amended to enable self-service registration on the `alpenflight` realm: the login page surfaces a "Sign up" link; the registration form requires email + password (+ first/last name as Keycloak built-ins).
  - Google is wired as a federated IdP: the login page shows a "Continue with Google" button; OIDC code flow round-trips to Google and back; first-time Google logins are auto-registered as Keycloak users (no manual approval).
  - Email verification is required: new users (both flavors) receive a verification email; unverified users cannot proceed past the post-signup landing.
  - Post-signup landing routes by `intent` query param. `migrate` → `/migrate/start` (the JAR-download flow, see S-139–S-141); `demo` is coerced to `/migrate/start` (refinement grill — `/demo` is anonymous-pre-signup, owned by S-136); default → `/migrate/start`.
  - A funnel-telemetry event `signup.completed` fires with `idp ∈ { local, google }` and `intent` (see S-147).
  - No tenant is created at signup. Tenant creation happens at first-successful-ingest (S-138).
estimate: M
adr_refs: [0007, 0018]
integration_base: integration/users-suite
parity_test: tests/public/signup.spec.ts (new)
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
context7_last_checked: 2026-05-27
github_issue: 143
github_pr: 144
---

## Context

Vision C26 mandates self-service signup with Google IdP federation. S-019 (Keycloak realm) and S-021 (Angular OIDC client) cover login but not signup. This story extends both. Tenant provisioning stays out (C25 lifecycle: signup → no tenant yet → `trial` only on first ingest), keeping signup cheap and avoiding zombie tenants.

## Load-bearing decisions

- **`trustEmail=false` on the Google IdP.** Closes the auto-link-to-unverified-local hijack: even with a verified Google email, Keycloak's stock `first broker login` flow sends a verify-mail challenge to the existing local account before linking. `verifyEmail=true` is the partner pin. Subsequent logins do not re-verify (Keycloak marks `emailVerified=true` after first pass).
- **No tenant created at signup.** Federated users carry no `clubId` claim; S-022's `ClubTenantIdentifierResolver` already tolerates `clubId: null` per S-019's cross-story contract. Trial Deployment provisioning is S-138's job, fired on first successful ingest (S-141).
- **`intent` carrier reuses S-021's `post-login-redirect` sessionStorage allowlist.** Same auth-owned `sessionStorage` site as deep-link preservation — no new persistence sites. SPA route resolver enum-coerces `intent` to `migrate` server-authoritatively (open-redirect closure); the router never `navigateByUrl`s a raw string.
- **`signup.completed` fires SPA-side once per round-trip.** Keyed by a one-shot `alpenflight.signup-pending` stamp set at `/signup` and consumed by `/migrate/start`. Server-side Keycloak event-listener was the alternative but needs a custom SPI JAR and doesn't see `intent`. The S-147 emitter is a placeholder (`console.info` sink — the Playwright PII assertion gates on the `[funnel]` prefix + `console.info` message type; S-147 swap-out will break that intentionally).
- **Bot mitigation scope.** Realm `passwordPolicy="length(12) and notUsername and notEmail and specialChars(1)"` lands here; per-IP rate-limit on `/login-actions/registration` → S-041, nightly purge of `email_verified=false` accounts > 14 d → S-038 (or new follow-up). Keycloak's `bruteForceProtected=true` covers login flooding, not registration flooding — surfaced gap, not closed here.
- **SMTP is load-bearing.** No `smtpServer` config → verify-email issues silently die → local-signup users permanently stuck. `mailpit:1025` covers dev (compose service); prod cutover (S-151) swaps via env.
- **Secret-leak guard.** `check-realm-shape.sh` asserts `identityProviders[?alias=='google'].config.client(Id|Secret)` are literal `${env:...}` strings — same shape-guard pattern as the existing no-private-key assertion. Catches a sloppy `export-realm.sh` round-trip that pulled a real prod secret into the committed file.

## AC drift to clean up at finalize

AC4's `demo` → `/demo` branch contradicts the grilled design (`intent=demo` is silently coerced to `/migrate/start` because `/demo` is anonymous-pre-signup, owned by S-136). Wording rewritten in the frontmatter above; check that `/modernize-finalize` notices.

## Parity strategy

N/A — greenfield. Legacy FLS has `/Token` password-grant only; no self-service signup, no IdP federation, no email verification. No oracle to validate against.

## Pickup notes

- The real-Keycloak end-to-end signup spec (verify-mail click-through, Google IdP stub against `accounts.google.com`, unverified-user gate) is deferred to the S-021 follow-up real-OIDC Playwright harness. The mock-auth Playwright spec at `alpenflight/web/e2e/tests/public/signup.spec.ts` covers the SPA-side contract (intent resolution, customParams shape, signup-pending one-shot consume, PII-free funnel payload).
- Local dev without Google credentials: `KEYCLOAK_GOOGLE_CLIENT_ID/SECRET` carry placeholder defaults in `docker-compose.yml`. The realm renders a Google button; clicking it surfaces Keycloak's `invalid_client` page. Flip `SIGNUP_FEATURE_FLAGS.googleSignupEnabled = false` in `alpenflight/web/src/app/features/signup/signup.config.ts` to hide the SPA-side CTA locally. Operator runbook in `alpenflight/auth/README.md` covers Google Cloud Console setup.
