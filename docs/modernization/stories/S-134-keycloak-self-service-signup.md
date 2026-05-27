---
id: S-134
title: Keycloak self-service signup + Google IdP federation
epic: E-15
status: todo
depends_on: [S-019, S-021]
acceptance:
  - The Keycloak realm export (S-019) is amended to enable self-service registration on the `alpenflight` realm: the login page surfaces a "Sign up" link; the registration form requires email + password (+ first/last name as Keycloak built-ins).
  - Google is wired as a federated IdP: the login page shows a "Continue with Google" button; OIDC code flow round-trips to Google and back; first-time Google logins are auto-registered as Keycloak users (no manual approval).
  - Email verification is required: new users (both flavors) receive a verification email; unverified users cannot proceed past the post-signup landing.
  - Post-signup landing routes by `intent` query param: `migrate` → `/migrate/start` (the JAR-download flow, see S-139–S-141); `demo` → `/demo`; default → `/migrate/start`.
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
---

## Context
Vision C26 mandates self-service signup with Google IdP federation. The existing S-019 (Keycloak realm) and S-021 (Angular OIDC client) cover login but not signup. This story extends both: realm config enables registration + Google IdP; SPA shows the signup affordances and handles the post-signup `intent` routing.

Tenant provisioning is deliberately NOT in this story (see C25 lifecycle: signup → no tenant yet → `trial` on first ingest). This keeps signup cheap and avoids accumulating zombie tenants from people who sign up and bounce.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Update Keycloak realm export: `registrationAllowed=true`, `verifyEmail=true`, Google IdP config (client_id placeholder; real secret in env).
- [ ] Add Angular routes: `/signup`, `/migrate/start`, `/demo` (the last delegated to S-136 for behavior).
- [ ] Post-signup `intent` cookie / query-param propagation.
- [ ] Document Google IdP setup in the operator runbook (where to register the OAuth client; redirect URIs).
- [ ] Funnel-telemetry hookup.

## Notes
- Google client_id / client_secret live in env, never committed. Realm export references them via Keycloak's env-var substitution syntax.
- Anonymous demo sessions (S-136) do NOT touch Keycloak — they use a server-issued signed cookie, not an OIDC token. Keep the two surface areas clearly separate in code.

<!-- modernize-refine: start -->

## Design notes

**Schema impact: zero.** Pure Keycloak realm-export delta + SPA routing + SMTP config. S-022's `ClubTenantIdentifierResolver` already tolerates `clubId: null` for federated users (S-019 cross-story contract).

**Realm-export deltas** (`alpenflight/auth/realm-export.json`, round-tripped via `scripts/export-realm.sh`):
- `registrationAllowed: true` (flip from S-019). `registrationFlow="registration"` and `verifyEmail=true` already wired.
- `identityProviders[]` gains a Google entry: `providerId="google"`, `alias="google"`, **`trustEmail=false`** (operator-pinned — see Security plan), `syncMode="force"`, `defaultScope="openid profile email"`, `clientId=${env:KEYCLOAK_GOOGLE_CLIENT_ID}`, `clientSecret=${env:KEYCLOAK_GOOGLE_CLIENT_SECRET}`, no `hostedDomain`. Stock `first broker login` flow alias — no custom flow.
- `smtpServer` block: env-driven (`${env:KEYCLOAK_SMTP_HOST/PORT/FROM/USER/PASSWORD/AUTH/STARTTLS}`). Dev maps to compose `mailpit:1025`; prod swaps via env.
- `passwordPolicy="length(12) and notUsername and notEmail and specialChars(1)"` (per Security plan).
- `check-realm-shape.sh` extensions (asserted in CI): `registrationAllowed=true`; `smtpServer` non-empty; Google entry has `trustEmail=false`, stock `firstBrokerLoginFlowAlias`, no per-IdP token overrides; `config.clientId`/`config.clientSecret` are literal `${env:...}` substitution strings (no real secrets); `passwordPolicy` carries the four rules above.

**Intent propagation reuses S-021's `post-login-redirect` sessionStorage allowlist.** `/signup` reads `?intent=migrate|demo`, resolves to a target path (per operator grill: both branches plus missing/garbage → `/migrate/start`; `/demo` is anonymous-pre-signup, owned by S-136), stamps `alpenflight.post-login-redirect`, then calls `oidcSecurity.authorize({...})`. `OidcSessionBridge` consumes the stamp post-callback. No new persistence sites — same auth-owned `sessionStorage` allowlist as deep-link preservation.

**SPA call-sites:**
- New route `/signup` (`publicAccess: true`, lazy via `loadChildren`): two CTAs. "Sign up" → `authorize({ customParams: { prompt: 'create', ui_locales: 'de' } })`. "Continue with Google" → adds `kc_idp_hint: 'google'`. Google button hidden when `environment.googleSignupEnabled === false` (dev default; flipped to true at prod build).
- New route `/migrate/start` (`publicAccess: false`, `authGuard`): placeholder owned/replaced by S-141.
- `/demo` route NOT registered here — S-136 owns it.

**Funnel telemetry — SPA-side emission, keyed by a one-shot stamp.** `/signup` writes `alpenflight.signup-pending` to sessionStorage at authorize-call time. Post-callback, the landing emits `signup.completed` once (consumes the stamp). `idp` derived from the token's `identity_provider` claim (Keycloak stamps `google` for federated; absent for local). Server-side Keycloak event-listener rejected: would need a custom SPI JAR, out of scope; doesn't see `intent`.

**Operator runbook extends `alpenflight/auth/README.md`** (no new file): Google Cloud Console OAuth client setup, redirect URI `${KEYCLOAK_PUBLIC_URL}/realms/alpenflight/broker/google/endpoint`, env-var checklist, SMTP dev/prod guidance, how to test signup locally without a real Google client (Google button stays hidden via `environment.googleSignupEnabled=false`).

**AC drift to flag for `/modernize-decompose`** (refine cannot edit ACs): AC3 stands as written (verify-email at signup for both flavors). AC4 is superseded by the grill — `intent=demo` is silently coerced to `/migrate/start`. The implementer follows this design; AC4 wording should be brought into line during finalize.

## Edge cases & hidden requirements

- **Two flavors, one verify step at signup; no re-verification on subsequent logins.** `trustEmail=false` + `verifyEmail=true` means even Google-federated first logins run Keycloak's `VERIFY_EMAIL` action before tokens are issued. Once verified, `emailVerified=true` sticks on the Keycloak user — subsequent logins (local or Google) do not re-verify.
- **SMTP is load-bearing.** No `smtpServer` config → `VERIFY_EMAIL` issues but no mail is sent → local-signup user is permanently stuck. Mailpit (`mailpit:1025`) covers dev; prod cutover (S-151) swaps to a real provider via env.
- **First-broker-login flow stays stock.** Keycloak's built-in flow auto-links a Google identity to an existing local account only after a verify-mail-to-existing-account challenge when `trustEmail=false`. Custom flows are explicitly out of scope; `check-realm-shape.sh` asserts the realm still references the stock flow.
- **Orphan unverified accounts accumulate.** Out of scope per operator grill. Follow-up owner: nightly `keycloak.unverified-user.purge` job (≥ 14 d old, `email_verified=false`) lands in S-038 or a new story.
- **Token policy continuity.** Realm-level `accessTokenLifespan` / `revokeRefreshToken` apply to federated sessions identically; per-IdP overrides are forbidden by the realm-shape guard.
- **`prompt=create` is the deep-link, not `/registrations`.** Keycloak 26 marks the path form deprecated; the SPA's "Sign up" CTA goes via `authorize({ customParams: { prompt: 'create' } })` on the existing OIDC library. No realm-level URL is baked into the SPA.
- **Dev placeholder strategy avoids broken-button first impression.** `environment.googleSignupEnabled` boolean gates the SPA's Google button; the realm exports stays env-substituted (no literal secrets). Operator opts in locally by setting both the env var pair AND the flag.

## Security plan

**Account hijacking via Google federation — mitigated by `trustEmail=false`.** Attacker pre-creates Keycloak local user `victim@x.com` (unverified) → victim signs in with Google. With `trustEmail=false` + `verifyEmail=true`, Keycloak's stock `first broker login` flow sends a verify-mail challenge to the email address before any auto-link, closing the path.

**Bot / throwaway-domain signup flood.** This story declares only `passwordPolicy="length(12) and notUsername and notEmail and specialChars(1)"` on the realm. Per-IP rate-limit on `/login-actions/registration` → S-041 (reverse proxy). Nightly purge of `email_verified=false` users > 14 d → S-038 (or new follow-up story). Keycloak's `bruteForceProtected` covers login flooding, not registration flooding; surfaced gap, no in-story closer.

**Google `client_secret` leakage via `export-realm.sh` round-trips.** `check-realm-shape.sh` asserts `identityProviders[?alias=='google'].config.clientSecret` equals the literal string `${env:KEYCLOAK_GOOGLE_CLIENT_SECRET}` — not a hex blob, not `**********`. Same shape-guard pattern as the existing no-private-key assertion.

**Open-redirect via `intent` query param.** SPA route resolver enum-coerces `intent` server-authoritatively: `migrate` is the only resolved target; `demo`, missing, and unknown values are silently coerced to `migrate`. The router never calls `Router.navigateByUrl(intent)` with a raw string.

**PII in `signup.completed`.** Payload: `{ event_id, actor_id: <keycloak sub UUID>, idp: "local" | "google", intent: "migrate", timestamp }`. No email, no name, no raw IP. Keycloak's own event log (`eventsEnabled=true`, S-019) retains the forensic auth trail (email + IP + IdP); that surface is the GDPR data-subject access path, not S-147.

**GDPR / FADP — orphan verified KC users.** A user who verifies email but never ingests persists in Keycloak with PII. Deletion path before first ingest: manual KC admin delete (no app DB rows exist yet); documented in `alpenflight/auth/README.md`. A scheduled-cleanup for verified-but-never-ingested users is not in scope for E-15.

**CSP / framing.** Google's auth page is browser-navigated, not iframe-embedded; S-041's existing `form-action 'self' <keycloak>` already covers. No new CSP knob.

## Test plan

**Unit (Vitest, SPA) — 3 cases.**
- `intent` resolver: `migrate` / `demo` / missing / garbage → all resolve to `/migrate/start`.
- `customParams` builder: "Sign up" → `{ prompt: 'create', ui_locales: 'de' }`; "Continue with Google" → adds `kc_idp_hint: 'google'`.
- `signup.completed` emission gate: fires once when `signup-pending` stamp is present at post-callback; idempotent on re-render; PII-free payload.

**Realm-shape (`check-realm-shape.sh`, S-153 territory) — 5 invariants.**
- `registrationAllowed=true` AND `verifyEmail=true`.
- `identityProviders[?alias=='google']` exists with `providerId="google"`, `trustEmail=false`, stock `firstBrokerLoginFlowAlias="first broker login"`, no per-IdP token overrides.
- `config.clientId` AND `config.clientSecret` are literal `${env:...}` substitution strings.
- `smtpServer` non-empty (env-driven host/port).
- `passwordPolicy` includes `length(12)`, `notUsername`, `notEmail`, `specialChars(1)`.

**Backend integration:** none — Keycloak owns the flow; `signup.completed` is SPA-emitted.

**E2E (Playwright, `tests/public/signup.spec.ts`, real Keycloak via `dev-up-full.sh`) — 5 cases.**
- Local signup happy path + `intent=migrate`: form → submit → fetch verify-email from mailpit → click link → lands on `/migrate/start`.
- Local signup with `intent=demo` query: lands on `/migrate/start` (the silent coercion).
- Unverified-user gate: complete form, do NOT click the verify link, attempt to navigate past the post-signup landing → blocked (Keycloak holds tokens until verified).
- Google IdP happy path: click "Continue with Google" → `kc_idp_hint=google` carried on the authorize URL → stubbed Google authorize/token endpoints → Keycloak issues verify-email (because `trustEmail=false`) → fetch + click verify link → lands on `/migrate/start`. Funnel event carries `idp=google`.
- Funnel-event PII assertion: `signup.completed` payload contains no email, no `given_name`/`family_name`, no raw IP.

**Parity strategy: N/A** — greenfield. Legacy FLS has `/Token` password-grant only; no signup, no IdP federation, no verify-email.

**Fixtures.**
- Mailpit helper (`tests/support/mailpit.ts`, new): bounded poll on `GET http://localhost:8025/api/v1/messages?query=to:<addr>`; extract action link; `DELETE /api/v1/messages` per-test cleanup.
- Google IdP stub (`tests/support/google-idp-stub.ts`, new): `page.route` intercepts `accounts.google.com/o/oauth2/v2/auth` (302 to Keycloak `/broker/google/endpoint` with synthetic `code`+`state`) and `oauth2.googleapis.com/token` (returns minted ID token with `email_verified=true`, `sub`, `email`, `given_name`, `family_name`). Reusable by future Google-federation specs.
- Unique email per run (`signup+${Date.now()}@example.test`); `afterEach` cleanup via Keycloak Admin REST `DELETE /users/{id}`.

## Performance plan

(N/A — signup is one-off per user; no DB hot path; estimate `M`, no perf-relevant signal.)

<!-- modernize-refine: end -->
