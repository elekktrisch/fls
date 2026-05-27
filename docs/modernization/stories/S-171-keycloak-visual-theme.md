---
id: S-171
title: Keycloak login/account/email visual theme — brand parity with the SPA
epic: E-03
status: todo
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

S-019 ships the `alpenflight` realm with Keycloak's stock `keycloak.v2` theme. S-134 turns on self-service signup + Google IdP, so end-users now spend real time on Keycloak-hosted pages (login, register, verify-email, password-reset, idp-link-confirmation, account console). The visual jump from the SPA's slate-on-white / brand-blue look (ADR 0024) to Keycloak's default purple-on-grey breaks the perceived continuity of the signup funnel — flagged informally during S-134's manual smoke.

**Scope is visual only.** Logo + favicon + colors + typography baseline against Keycloak's existing templates. NOT in scope: FreeMarker template restructuring, custom flows, custom i18n message bundles, account-console layout overrides, custom required-action screens. Per ADR 0022 directive 1 (working software over docs) the smallest change that closes the visual jump.

S-100's body explicitly punted Keycloak theming to "a future story" — this is it.

## Acceptance criteria

- A new theme directory `alpenflight/auth/themes/alpenflight/` ships:
  - `theme.properties` declaring `parent=keycloak.v2`, `import=common/keycloak`, the theme types it covers (`login`, `account`, `email`), and the locale list `de en fr it`.
  - `login/resources/css/login.css` overriding only the design-token-equivalent CSS variables Keycloak v2 exposes (primary color → brand-500 OKLCH, danger color, neutral surface, border radius → 2 px max per ADR 0024).
  - `login/resources/img/alpenflight-logo.svg` + `favicon.ico` — the AlpenFlight wordmark + plane glyph matching the SPA header at `landing.component.ts`. Single-color SVG so it tints with the primary token.
  - `email/html/event-NNN.ftl` files are NOT overridden; the realm's `emailTheme=alpenflight` reuses parent templates but picks up the brand assets via `import=`.
- The custom theme is baked into the `alpenflight-keycloak:local` Docker image (parallel to how `realm-export.json` is baked per `alpenflight/auth/Dockerfile`). Bind-mount NOT used — same Windows-Docker-Desktop file-share gotcha S-019 documents.
- `realm-export.json` references the theme: `loginTheme=alpenflight`, `accountTheme=alpenflight`, `emailTheme=alpenflight`. Round-trip via `scripts/export-realm.sh` stays zero-diff.
- `check-realm-shape.sh` asserts the three theme references are pinned to `alpenflight` (drift guard — accidental re-export from a Keycloak admin UI that defaulted them back to `keycloak.v2` fails CI).
- Locale parity holds. The four supported locales (de/en/fr/it per S-019's `internationalizationEnabled=true`) render the Keycloak login form with German/English/French/Italian copy from Keycloak's built-in message bundles. The theme does NOT ship its own message bundles; locale fallback to `parent=keycloak.v2` covers all labels.
- Touch-target NFR (vision §2, ≥ 44 × 44 CSS px on mobile) holds on Keycloak's login page at 360 × 640. CSS overrides preserve or grow the existing v2 button sizing; do not shrink.
- Operator runbook (`alpenflight/auth/README.md`) gains a "Theme" section: where assets live, the round-trip workflow (rebuild image after edits), how to preview locally (`http://localhost:8090/realms/alpenflight/account` for the account console).
- A manual smoke (operator-driven, documented in the runbook) cycles through: `/realms/alpenflight/account` rendering with the new wordmark + brand color; `/signup` SPA route → Keycloak register form rendered with brand colors; locale switch via `?ui_locales=fr` flips Keycloak labels to French; mailpit-delivered verify-email contains the wordmark.

## Notes

- **Why bake, not bind-mount.** Same reasoning as S-019's realm-export decision: Docker Desktop's bind-mount on Windows hosts is unreliable for directory trees. The Dockerfile `COPY themes/ /opt/keycloak/themes/` keeps the dev round-trip = rebuild image. Documented gotcha; not a deviation.
- **Why parent=keycloak.v2, not keycloak.** `keycloak.v2` is Keycloak 24+'s React-rebuilt login theme and is on the supported track; the legacy `keycloak` theme is EOL by Keycloak 27 per the Keycloak 26 release notes. Pin against v2.
- **Asset source.** Wordmark SVG comes from the same source as the SPA header (`alpenflight/web/src/app/features/landing/landing.component.ts` currently inlines a `<af-icon name="plane">` + text). Extract once to a shared `alpenflight/branding/` folder; both the SPA and the Keycloak theme reference it. Out of scope for this story: doing the extraction. In scope: the Keycloak theme ships its own SVG copy with a `# source: alpenflight/branding/wordmark.svg` comment.
- **i18n strategy explicitly punted.** No custom message bundles. If the operator decides a Keycloak button label needs to read differently from the stock translation, that's a follow-up story (probably needs a `messages/messages_<lang>.properties` per locale and a CI gate that the four files stay in shape — non-trivial scope; not justified by current evidence).
- **Out of scope explicitly noted to surface in the PR description, not the code:**
  - Per-club whitelabel branding on Keycloak (C19 caps that to nav-bar + splash + primary color on the SPA only; Keycloak stays single-brand AlpenFlight).
  - Theming for IdP-broker pages beyond what `parent=keycloak.v2` provides.
  - Custom required-action screens (the `VERIFY_EMAIL` and `UPDATE_PASSWORD` pages inherit from parent).
- **Refinement candidates** (for `/modernize-refine S-171` to surface decisions on):
  - Exact CSS variable list to override vs accept-default — resolved (Design notes: PF5 v5 globals + targeted component-class rules where PF5 doesn't surface a token).
  - Whether to ship a `properties` file declaring assets — resolved (Design notes: 3 per-type `theme.properties`, `styles=` lists parent CSS first then overrides; required).
  - Whether the realm-shape guard regression-locks the favicon path — resolved (no; `theme.properties` is the contract).

<!-- modernize-refine: start -->

## Design notes

- **Three `theme.properties`, one per type, three different parents.** K26.5 ships three independent parent tracks; the story's blanket `parent=keycloak.v2` AC is imprecise. Pin:
  - `login/theme.properties` → `parent=keycloak.v2` (supported login track; legacy `keycloak` is EOL).
  - `account/theme.properties` → `parent=keycloak.v3` (K26.5 default; the Quarkus/React account console — operator-confirmed, see AC drift).
  - `email/theme.properties` → `parent=keycloak` (the email theme has never been re-platformed; `keycloak.v2` does not exist for emails).
  Each carries its own `locales=de,en,fr,it`. `import=common/keycloak` is login-only; account-v3 and email do not consume `common/`.
- **`styles=` must list parent stylesheets first, then overrides.** Keycloak does NOT auto-merge parent + child `styles`. Read the actual list from `/opt/keycloak/themes/keycloak.v2/login/theme.properties` inside the running image; do not guess. Omitting parent CSS renders the form unstyled; listing the override before the parent breaks the cascade.
- **CSS override surface — PF5 globals + non-variable component-class rules.** Brand-bridge:
  - Globals: `--pf-v5-global--primary-color--100/200`, `--link--Color` (+ hover), `--danger-color--100`, `--success-color--100`, `--warning-color--100` (last two cover S-134's password-strength meter), `--BackgroundColor--100`, `--BorderColor--100`, font-family stack.
  - Sharp corners need a non-variable rule (PF5 component padding/radius isn't surfaced as tokens): `.card-pf, .pf-v5-c-card, .pf-v5-c-button, .pf-v5-c-form-control { border-radius: 0; }`. AC §25's "only the CSS variables" wording is too tight — extend scope to component-class rules where PF5 forces it.
  - Touch-target NFR (≥44 × 44 px on mobile) holds by parent-theme inheritance; PF5 stock button padding meets it and the override above does not shrink it. Not asserted by this story; not regressed.
  - OKLCH → PF5 mapping: emit hex/rgb-equivalents at the variable assignment because PF5 v5 color-math uses filter/mix-blend-mode internally; reference targets are in `docs/modernization/design-reference/tokens.css` + `alpenflight/web/src/styles.css`.
- **Realm-export round-trip injection (required).** Verified empirically: `partial-export` does NOT carry `loginTheme/accountTheme/emailTheme` when the realm holds them as plain root-level keys; `scripts/normalize-realm-export.sh` currently does not inject them. Extend the normalizer to inject the three keys post-export, mirroring the existing key-injection pattern, so `realm-export.json` stays zero-diff across round-trips.
- **`check-realm-shape.sh` must fail closed on `null`/missing.** Three new jq asserts — `loginTheme/accountTheme/emailTheme == "alpenflight"` — reject the "default state of a freshly-imported realm has no theme keys at all" case, not only the wrong-value case. No asset-path asserts; `theme.properties` is the contract.
- **Asset shipping per type.** Wordmark SVG + favicon ship under both `login/resources/img/` and `account/resources/img/` — `import=common/keycloak` is login-only, so account needs its own copy. Single-color SVG (per AC) tints via `fill: currentColor` + `color: var(--pf-v5-global--primary-color--100)`. SVG header comment `<!-- source: alpenflight/branding/wordmark.svg (not yet extracted; tracked separately) -->`; extraction deferred.
- **Dev cache.** `start-dev` (compose default) auto-disables theme cache; iteration loop is `docker compose build keycloak && up -d --force-recreate keycloak`. Document in README. **S-151 (production `start` mode) will need `--spi-theme-cache-themes=false` during cutover OR a forced image rebuild** — flag for S-151 scope, not closed here.
- **IdP-broker confirmation page** (Google first-login from S-134) inherits from `loginTheme`. Manual smoke adds one Google round-trip click-through.

## Edge cases & hidden requirements

- Realm-export round-trip drops theme refs unless `normalize-realm-export.sh` injects them — verified empirically; mitigation in Design notes.
- Account console default in K26.5 is `keycloak.v3`, not `keycloak.v2` — story's blanket `parent=keycloak.v2` needs per-type breakdown (see AC drift).
- The wordmark cannot reach the verification-email body without an FTL override (which contradicts the "no FreeMarker rewrites" scope cap) — the AC asserting "mailpit verify-email contains the wordmark" is unachievable as scoped; resolved by dropping that AC line (see AC drift).
- `check-realm-shape.sh` must fail closed on `null`/missing theme refs, not only on the wrong value (defaults-state catch).
- Production deploy (S-151) inherits the dev image — baked theme transfers, but `start` mode caches stylesheets unless explicitly disabled. Flag for S-151.
- Touch-target NFR is satisfied by parent inheritance, not by this story's overrides — PF5 component-internal padding isn't surfaced as a CSS variable; the sharp-corner override does not regress button size.

## Security plan

- **SVG-XSS rule (load-bearing).** All SVGs under `themes/alpenflight/*/resources/img/` are hand-authored single-color files with no `<script>`, no `<foreignObject>`, no `on*` attributes, no external `xlink:href`. Same-origin as the realm = inline `<script>` executes against Keycloak's origin and can read auth-session data. Enforced via code review on the diff.
- **No FreeMarker overrides (scope cap, load-bearing).** No `*.ftl` files under `themes/alpenflight/`. Keeps the story out of the user-attribute-interpolation threat surface. Enforce via PR checklist (`alpenflight/auth/themes/alpenflight/**` must contain no `.ftl`).
- **CSS scope discipline.** Overrides hit design-token vars + brand-bridge component classes only. Code review rejects rules that `display:none` chrome (verify-email banner, realm-name header, error/warning text) — these are the CSS-side phishing vectors.
- **Realm-shape drift.** S-019's existing security pins (PKCE-S256, no private key, registration/brute-force settings, `${env:...}` literal Google secret) are not relaxed by the three new theme-ref asserts.
- **N/A: tenant isolation, authz, PII, audit-log.** Theme is realm-global static assets; no `@TenantId` boundary, no DB writes, no user data rendered by code this story writes.

## Test plan

- **Realm-shape guard (must-have).** Extend `alpenflight/auth/scripts/check-realm-shape.sh` with three jq asserts: `loginTheme/accountTheme/emailTheme == "alpenflight"`, failing closed on `null`/missing. Already wired through CI gate `next-auth-realm-shape`.
- **Theme-load smoke (must-have).** Bash assertion in `alpenflight/auth/scripts/dev-up-full.sh` (or sibling): after Keycloak healthcheck, `curl` the OIDC auth endpoint and grep for the `alpenflight/` substring in the resource path. Covers Dockerfile COPY typos + theme-dir layout mistakes the shape guard can't see. Keep the grep loose (substring, not full hashed path) so it survives Keycloak minor upgrades.
- **Locale smoke (cheap).** Same script: hit `?kc_locale=fr` and `?kc_locale=it`, grep for one known-translated stock label per locale. Confirms message bundles load via parent inheritance.
- **Manual visual smoke (operator runbook in the story AC).** One pass per theme edit: login form, signup form, account console landing, `?ui_locales=fr` locale switch, IdP-broker "Continue with Google" confirmation page, mailpit verify-email body (brand-color check — wordmark intentionally not in scope).
- **Touch-target (skip / defer).** No Playwright infra hits live Keycloak today; mock-auth bypass + the `e2e/` legacy suite shape don't cover Keycloak. Stock keycloak.v2 buttons are ≥44 px and overrides do not shrink them. Defer to a future Keycloak-targeted Playwright project; coverage gap noted, not a blocker for this story.
- **Parity strategy:** N/A — greenfield. flsweb has no Keycloak; no legacy oracle exists.

## Performance plan

(N/A — visual theme. No query paths, hot endpoints, caching layers, or perceived-latency budgets touched. Theme assets are static SVG + CSS baked into the Keycloak image, served by Keycloak with its existing static-asset cache headers.)

## AC drift to clean up at finalize

- **AC §23** (`theme.properties declaring parent=keycloak.v2 ... covering login/account/email`) → 3 per-type files with different parents: `login`→`keycloak.v2`, `account`→`keycloak.v3` (operator-confirmed 2026-05-27, matches K26.5 default), `email`→`keycloak` (only email parent shipped).
- **AC §34** (`mailpit-delivered verify-email contains the wordmark`) → drop. Operator-confirmed 2026-05-27: emails inherit brand colors via stock parent templates; wordmark-in-email needs an FTL override which contradicts the "no FreeMarker rewrites" scope cap. Brand-color check on the email body stays; wordmark check goes.

<!-- modernize-refine: end -->
