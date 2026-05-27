---
id: S-171
title: Keycloak login/account/email visual theme — brand parity with the SPA
epic: E-03
status: done
started_at: 2026-05-27
done_at: 2026-05-27
estimate: M
depends_on: [S-019, S-134]
adr_refs: [0007, 0024]
integration_base: integration/users-suite
parity_test: none
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
context7_last_checked: 2026-05-27
github_issue: 145
github_pr: 146
---

## Context

S-019 ships the `alpenflight` realm with Keycloak's stock theme; S-134 turns on self-service signup + Google IdP so end-users spend real time on Keycloak-hosted pages. The default purple-on-grey breaks visual continuity with the SPA's slate + brand-blue look (ADR 0024). Scope is the smallest visual fix: logo + favicon + colors + typography on top of stock templates.

## Acceptance criteria

- Theme directory `alpenflight/auth/themes/alpenflight/` ships per-type files:
  - `login/theme.properties` → `parent=keycloak.v2`, `import=common/keycloak`, `styles=css/styles.css css/login.css`, `favIcon=img/favicon.ico`, `locales=de,en,fr,it`.
  - `account/theme.properties` → `parent=keycloak.v3` (K26.5 default React account console), `locales=de,en,fr,it`.
  - `email/theme.properties` → `parent=keycloak` (only email parent shipped), `locales=de,en,fr,it`.
  - `login/resources/css/login.css` overrides PF5 v5 globals (primary / link / danger / warn / ok / surface / border / font) plus sharp-corner rules on `pf-v5-c-*` component classes (PF5 doesn't surface radius as a token).
  - Wordmark SVG + favicon ship under both `login/resources/img/` (v2 convention) and `account/resources/` (v3 convention; `import=common/keycloak` is login-only).
- Theme baked into `alpenflight-keycloak:local` (no bind-mount — Docker Desktop Windows file-share gotcha per S-019).
- `realm-export.json` pins `loginTheme`/`accountTheme`/`emailTheme=alpenflight`; `scripts/normalize-realm-export.sh` injects the three keys post-export so round-trip stays zero-diff (partial-export drops them — verified empirically on K26.5).
- `check-realm-shape.sh` asserts the three theme refs `== "alpenflight"`, failing closed on `null`/missing (defaults-state catch).
- Locales render via parent message-bundle inheritance for de/en/fr/it; theme ships no message bundles.
- Operator runbook (`alpenflight/auth/README.md`) gains a "Theme" section + a `check-theme-load.sh` operator smoke against a running Keycloak (not wired to CI — no live Keycloak for the realm).
- Manual smoke matrix in the runbook covers: login, signup, account console, locale switch via `?kc_locale=fr`, Google IdP confirmation, mailpit verify-email body brand-color check (wordmark-in-email out of scope — needs FTL).

## Security invariants (load-bearing for future PRs)

- **No `.ftl` files under `themes/alpenflight/`.** Keeps the theme out of the user-attribute-interpolation threat surface.
- **No `<script>`, `<foreignObject>`, `on*` attributes, or external `xlink:href` in any SVG under the theme.** Same-origin as the realm = inline `<script>` runs against Keycloak's origin and can read auth-session data.
- **No CSS rules that hide chrome** (verify-email banner, realm-name header, error/warning text) — CSS-side phishing vector.

## Out of scope

- Per-club whitelabel branding on Keycloak (C19 caps to SPA only).
- FreeMarker / custom message bundles / required-action screens / wordmark-in-email.
- Touch-target Playwright coverage on Keycloak (no live-Keycloak Playwright project exists; PF5 stock buttons meet ≥44 px by parent inheritance + sharp-corner override doesn't shrink them).

## Notes for downstream

- **S-151 (production cutover):** `start-dev` auto-disables theme cache; production `start` mode caches stylesheets. S-151 needs either `--spi-theme-cache-themes=false` or a forced image rebuild on each theme change.
- **Shared `alpenflight/branding/` extraction deferred.** Theme currently ships its own copies of the wordmark SVG; extraction (so SPA + Keycloak reference one source) is a follow-up.
