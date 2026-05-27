---
id: S-173
title: Keycloak operator-env plumbing — Google OAuth + alpenflight-web baseUrl
epic: E-03
status: done
started_at: 2026-05-27
done_at: 2026-05-27
estimate: S
depends_on: [S-134, S-171]
integration_base: integration/users-suite
adr_refs: [0007]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
github_issue: 147
github_pr: 148
origin: punch-list
---

## Context

Two env-substitution holes blocked "fresh-clone → working stack":

1. **Google IdP secrets** (from S-134) — `realm-export.json` had `${env:KEYCLOAK_GOOGLE_*}` placeholders, but no `.env` workflow existed; click "Continue with Google" surfaced Keycloak's `invalid_client` page.
2. **alpenflight-web `baseUrl`** (from S-171) — the login-footer "Back to Start" link rendered a hardcoded `http://localhost:4200/` fallback; prod deployments needed the real SPA URL.

Both plugged via the same `.env.example` + `env_file` + realm-export-substitution scheme. Substitution-layer architecture documented in [`alpenflight/auth/README.md` § Substitution layers](../../../alpenflight/auth/README.md#substitution-layers).

## What actually shipped

- **`alpenflight/auth/.env.example`** committed with sentinel defaults; gitignored `alpenflight/auth/.env` overrides per-laptop. `docker-compose.yml` loads both via `env_file:` (ordering matters — example first, override second — and the env-var defaults MUST live in `.env.example`, not in the `environment:` block which would clobber `env_file`).
- **`alpenflight-web.baseUrl`** lands via a Dockerfile build-arg sed against `${ALPENFLIGHT_WEB_BASE_URL}`. Keycloak's realm-import substitution doesn't reach `client.baseUrl` (URL validator runs first) — see the README for the full three-layer story.
- `footer.ftl` drops the FreeMarker fallback → `${client.baseUrl}` verbatim.
- `check-realm-shape.sh` extended with the baseUrl + Google-secret + SMTP shape guards; `normalize-realm-export.sh` re-injects the placeholder on `export-realm.sh` round-trip.
- New `alpenflight/auth/scripts/check-keycloak-integration.sh` — admin-API direct check + PKCE-aware login-HTML scrape + verify-email round-trip via mailpit. Wired into `compose-smoke` CI; operator-runnable locally.

## Boy-scouts folded in

- **Verify-email FreeMarker NPE** (operator's `SEND_VERIFY_EMAIL_ERROR` from 2026-05-27). Root cause was Keycloak's realm-import resolver: `System.getenv(propertyName)` with the literal `env:VAR` string always returns null, then `StringPropertyReplacer`'s colon-fallback substitutes the post-colon literal var name into the realm. `smtpServer.from` baked as `"KEYCLOAK_SMTP_FROM"` → `isValidEmail` returns false → `from=null` → `mail.from` setProperty NPE. Stripped `env:` from all 9 `${env:KEYCLOAK_*}` markers in `realm-export.json`; same fix incidentally resolved the operator's `client_id=KEYCLOAK_GOOGLE_CLIENT_ID` Google-redirect symptom. S-134's `${env:...}` was broken from day one; SPA-mock Playwright tests never exercised the resolved IdP config.
- **PF5 warning-alert** dark surface on required-action screens (verify-email, update-password) → light warn-50 / warn-500 to match the alpenflight card aesthetic.
- **`alpenflight/ops/cleanup-test-user.sh`** — wipes a test user from Keycloak (alpenflight realm) + Postgres (`t_user.notification_email`) so signup can be replayed without `rebuild-keycloak.sh` + `compose down -v`. Localhost safety gate; psql server-side variable binding for the DELETE.
- **OIDC cross-tab state** (S-021 shipped; couldn't ride that). The Mailpit verify link opens a new tab and `angular-auth-oidc-client` raised "could not find matching config for state" because (a) sessionStorage is per-tab, and (b) without an explicit `configId` the library auto-generates per-instance, so storage keys diverge across tabs. Three coordinated fixes: swap `DefaultSessionStorageService` → `DefaultLocalStorageService`, pin `configId: 'alpenflight-web'`, **reorder the `{ provide: AbstractSecurityStorage, ... }` line to AFTER `provideAuth(...)`** — provideAuth registers its own default storage and last-provider-wins. CLAUDE.md §10 allowlists auth-owned files for `localStorage` writes. ADR 0007's short access-token lifespan + refresh-token rotation + no-reuse covers the XSS-window trade-off.

## Cross-story contracts

- **Consumes** S-134 (Google IdP env-substituted realm-export + sentinel default), S-171 (footer.ftl macro).
- **Produces for S-151 (prod cutover):** env-driven contract — deploy pipeline sets `KEYCLOAK_GOOGLE_*` + `KEYCLOAK_SMTP_*` + `ALPENFLIGHT_WEB_BASE_URL`. Realm-export contract unchanged across environments.

## Out of scope (carried as follow-up nudges)

- "Optional CTA hiding" on `/signup` when Google credentials are unset — grilled out 2026-05-27; the build-time `fileReplacements` seam on `signup.config.ts` is preserved for a future follow-up if it bites real users.
- Real-Google OAuth round-trip against `accounts.google.com` — manual operator-only; per-developer client + non-deterministic UA.
- Dark-theme regression on Keycloak's stock action-token landing page (post-verify-email link target) — separate theme work, S-171 follow-up.
- `rebuild-keycloak.sh` doesn't bring mailpit up alongside keycloak — operator hits `UnknownHostException: mailpit` if mailpit isn't already running. Minor; `dev-up-full.sh` is the right path.
