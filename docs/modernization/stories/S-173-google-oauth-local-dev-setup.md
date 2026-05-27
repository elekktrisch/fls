---
id: S-173
title: Keycloak operator-env plumbing — Google OAuth + alpenflight-web baseUrl
epic: E-03
status: todo
estimate: S
depends_on: [S-134, S-171]
integration_base: integration/users-suite
adr_refs: [0007]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
github_issue: 147
origin: punch-list
---

## Context

Two related env-substitution holes in the alpenflight realm-export that both block "fresh-clone → working stack":

1. **Google IdP (from S-134)** — `realm-export.json` has env-substituted `clientId` / `clientSecret` placeholders. Clicking "Continue with Google" surfaces Keycloak's `invalid_client` page until those env vars are set, and there's no documented `.env` workflow.
2. **alpenflight-web client `baseUrl` (from S-171)** — S-171's `footer.ftl` renders a "Back to Start" link via `${client.baseUrl!'http://localhost:4200/'}`. Today the `alpenflight-web` client has no `baseUrl` set, so the hardcoded dev fallback fires for everyone. Production deployments need the real SPA URL.

Both are env-substitution holes plugged the same way — `.env` file + env_file mount + env-substituted realm-export. Bundled so the dev-env scaffolding lands in one ship.

Google does support `http://localhost` (and `127.0.0.1`) as both an OAuth redirect URI and a JavaScript origin without requiring HTTPS — explicitly as a development carve-out. So a per-developer OAuth client *can* be created against the local Keycloak; what's missing is the convention.

## Acceptance criteria

### Operator setup

- [ ] `alpenflight/auth/.env.example` exists with all `KEYCLOAK_GOOGLE_*` (+ SMTP override + `ALPENFLIGHT_WEB_BASE_URL`) keys documented inline, with the expected Google Cloud Console redirect URI written verbatim.
- [ ] `alpenflight/auth/.env` is git-ignored (project root `.gitignore` extended).
- [ ] `docker-compose.yml`'s `keycloak` block reads `env_file: alpenflight/auth/.env` (or equivalent) so the operator's `.env` flows into the container without manual export.
- [ ] `alpenflight/auth/README.md` § "Google Cloud Console — one-time setup" expanded:
  - exact click-path through Google Cloud Console
  - exact redirect URI string (`http://localhost:8090/realms/alpenflight/broker/google/endpoint`)
  - exact JS origin (`http://localhost:8090`)
  - note: "use a test Gmail account; this consumes one of your free OAuth clients but is throwaway"
  - what to do when the consent screen blocks ("publish to test users" mode + add your own email)

### alpenflight-web `baseUrl` (from S-171)

- [ ] `realm-export.json` — `alpenflight-web` client gains `"baseUrl": "${ALPENFLIGHT_WEB_BASE_URL:-http://localhost:4200/}"`. Same env-substitution pattern S-134 already uses for `KEYCLOAK_GOOGLE_CLIENT_ID`. Dev default keeps the stack working out of the box; prod env sets the real SPA URL at deploy.
- [ ] `alpenflight/auth/themes/alpenflight/login/footer.ftl` — drop the hardcoded `'http://localhost:4200/'` fallback now that the realm-export's env-substitution covers both lanes. Final href becomes `${client.baseUrl}` (FreeMarker still tolerates missing, falls back to `#` if truly absent).
- [ ] `alpenflight/auth/scripts/check-realm-shape.sh` — assert `.clients[] | select(.clientId=="alpenflight-web") | .baseUrl` contains `${env:ALPENFLIGHT_WEB_BASE_URL` so a sloppy `export-realm.sh` round-trip that bakes a literal localhost URL fails CI loudly.
- [ ] Manual smoke: with `ALPENFLIGHT_WEB_BASE_URL=http://localhost:4200/`, clicking "Back to Start" on `/login` lands on the SPA root.

### Optional CTA hiding (only if env-unset)

- [ ] When `KEYCLOAK_GOOGLE_CLIENT_ID` is unset or equals the literal `set-via-env-for-google-signup`, the SPA's `/signup` route hides the "Continue with Google" CTA instead of letting the user click into a Keycloak error page. (Implementation hook: surface a `google_idp_enabled` boolean from a backend `/api/v1/config` endpoint, or a build-time env. Pick the cheaper path during refinement.)
- [ ] Existing `signup.spec.ts` updated to cover both states (Google CTA visible / hidden).

### Smoke

- [ ] Fresh-clone walkthrough: copy `.env.example` → `.env`, fill creds from Google Cloud Console, `bash alpenflight/ops/dev-up-alpenflight.sh`, click "Continue with Google" on `/signup` → consent screen → land on Keycloak's first-broker-login → verify-email via Mailpit → SPA receives session.
- [ ] `bash alpenflight/auth/scripts/check-realm-shape.sh` still passes (the env placeholders in `realm-export.json` are unchanged; the secret never lands in the committed file).

## Tasks

- [ ] Write `alpenflight/auth/.env.example` (Google keys + SMTP overrides + `ALPENFLIGHT_WEB_BASE_URL`).
- [ ] Extend `.gitignore` for `alpenflight/auth/.env`.
- [ ] Wire `env_file` on the keycloak service.
- [ ] Add `"baseUrl": "${ALPENFLIGHT_WEB_BASE_URL:-...}"` to the alpenflight-web client in `realm-export.json`.
- [ ] Drop the hardcoded `'http://localhost:4200/'` fallback in `alpenflight/auth/themes/alpenflight/login/footer.ftl`.
- [ ] Extend `check-realm-shape.sh` with the env-substitution assertion.
- [ ] Doc updates in `alpenflight/auth/README.md`.
- [ ] (Optional, decide in refinement) Backend `/api/v1/config.googleIdpEnabled` + SPA conditional render of the Google CTA.

## Notes

- Per-developer Google client: yes, every developer needs their own. Google explicitly forbids sharing OAuth clients across developers; the secret is a per-app secret. The `.env` convention keeps secrets per-laptop.
- Production uses a single hosted OAuth client; same `KEYCLOAK_GOOGLE_CLIENT_SECRET` env name as dev (the `ALPENFLIGHT_KEYCLOAK_GOOGLE_*` asymmetry called out before refinement is dropped — see grill 2026-05-27).
- Existing `check-realm-shape.sh` already asserts the placeholders stay literal in the committed JSON — a leaked secret via sloppy `export-realm.sh` round-trip fails CI loudly. Don't loosen.
- `client.baseUrl` is the Keycloak-standard field for "the application's main URL" — it also feeds the "back to application" link in the account console, so setting it benefits more than just the login footer. The env-substitution pattern matches what S-134 already established for Google.

<!-- modernize-refine: start -->

## Design notes

**Cross-story contracts.** Consumes S-134's two-layer env-substitution pattern (`${env:KEYCLOAK_GOOGLE_*}` bare in `realm-export.json` + compose-level `${VAR:-set-via-env-for-google-signup}` fallback). Consumes S-171's `footer.ftl` "Back to Start" macro. Produces for S-151 (prod cutover): env-driven contract — prod just exports the keys at deploy, no JSON branching. Produces for S-041 (reverse-proxy rate-limit): nothing structural.

**Two-layer env layering, unchanged from S-134.** `realm-export.json` holds bare `${env:VAR}` (no `:default`). Compose's `${VAR:-fallback}` owns the dev-default at compose-up. `alpenflight/auth/.env` (gitignored) lets the operator override per-laptop. Keycloak's `${env:VAR:default}` syntax exists and works but is rejected — would bury the dev-default deep in a 2800-line JSON, splitting the source of truth. `ALPENFLIGHT_WEB_BASE_URL` joins `KEYCLOAK_GOOGLE_*` + `KEYCLOAK_SMTP_*` with a compose-level `${ALPENFLIGHT_WEB_BASE_URL:-http://localhost:4200/}` default.

**baseUrl trailing slash.** `http://localhost:4200/` with trailing slash. `footer.ftl` renders the value verbatim as `href`; Keycloak's account-console "back to application" link uses the same convention. Pin in `.env.example` + README.

**footer.ftl shape (per grill 2026-05-27).** Drop the FreeMarker fallback entirely → `<a class="af-back-to-landing" href="${client.baseUrl}">`. If `client.baseUrl` is unset (impossible in practice — realm-export's env-substitution + `check-realm-shape.sh` assertion + compose `:-` default close all three holes), FreeMarker raises; the operator sees the regression loudly. No `!''` graceful degrade.

**Optional CTA-hide AC is OUT OF SCOPE (per grill 2026-05-27).** The AC §"Optional CTA hiding" is dropped from S-173. SPA continues to ship `SIGNUP_FEATURE_FLAGS.googleSignupEnabled = true`; clicking the Google CTA with no `.env` surfaces Keycloak's `invalid_client` page. The `signup.config.ts` seam (build-time `fileReplacements` per the file's own header comment) is preserved for a future follow-up if it bites real users. `signup.spec.ts` is untouched.

**Prod env-var alignment (per grill 2026-05-27).** `KEYCLOAK_GOOGLE_CLIENT_ID` / `KEYCLOAK_GOOGLE_CLIENT_SECRET` are used in BOTH dev and prod — the `ALPENFLIGHT_KEYCLOAK_GOOGLE_*` prefix from the original Notes is dropped. The committed README (`alpenflight/auth/README.md:143-144`) already documents this unified naming; only the S-173 body's Notes needed correction. `.env.example` uses bare `KEYCLOAK_GOOGLE_*`.

**`check-realm-shape.sh` delta.** One new assertion appended to the alpenflight-web client block (mirror the Google-IdP secret-leak guard at `check-realm-shape.sh:194-197`):

```bash
WEB_BASE_URL=$(jq -r '.clients[] | select(.clientId=="alpenflight-web") | .baseUrl // ""' "$EXPORT")
[[ "$WEB_BASE_URL" == '${env:ALPENFLIGHT_WEB_BASE_URL'*'}' ]] \
  || fail "alpenflight-web.baseUrl must be \${env:ALPENFLIGHT_WEB_BASE_URL...} substitution (got: '$WEB_BASE_URL' — looks like an export-realm.sh round-trip baked a literal URL)"
```

The trailing `'*'}'` anchors the closing brace so `${env:ALPENFLIGHT_WEB_BASE_URL_EXTRA}` doesn't slip through.

**`normalize-realm-export.sh` patch (load-bearing).** Add a `DEV_CLIENT_BASE_URLS` block mirroring the existing `DEV_CLIENT_SECRETS` re-injection. Keycloak's `partial-export` REST endpoint resolves `${env:...}` in memory and emits the resolved literal — without re-injection, every `export-realm.sh` round-trip would bake whatever ran at boot (`http://localhost:4200/`) into the committed JSON. The new `check-realm-shape.sh` assertion is the loud safety-net; the normalize patch is the healer.

**README.md delta.** Three sections grow under "Self-service signup + Google IdP (S-134)" + a new fact about `alpenflight-web`:
1. Google Cloud Console one-time setup — exact click-path; verbatim redirect URI `http://localhost:8090/realms/alpenflight/broker/google/endpoint`; verbatim JS origin `http://localhost:8090`; "publish to test users" + "use a throwaway test Gmail account" notes.
2. New "Operator env workflow" subsection — `cp alpenflight/auth/.env.example alpenflight/auth/.env`, per-key semantics, the `set-via-env-for-google-signup` sentinel as the "feature-off" signal, `docker compose up -d --force-recreate keycloak` to pick up rotation without dropping H2.
3. `ALPENFLIGHT_WEB_BASE_URL` documented under the alpenflight-web client docs — dev value `http://localhost:4200/`, prod = real SPA origin (trailing-slash), where it surfaces (login footer + account-console "back to application" link).

**Boy-scout bugfix — verify-email FreeMarker failure.** A real operator hit `SEND_VERIFY_EMAIL_ERROR / error="email_send_failed" / reason="Failed to template email"` on 2026-05-27 from a `verifyEmail=true` first-broker-login (S-134 path). FreeMarker raised inside `FreeMarkerEmailTemplateProvider.send` — the template-resolution failed BEFORE any SMTP call (mailpit is not involved in the failure). The most plausible root cause given our theme shape: `email/theme.properties` declares `locales=de,en,fr,it` but ships NO `email/messages/` directory; Keycloak's per-locale message-bundle resolution under the child theme + custom `locales=` declaration is a known edge case in 26.5. **Fix to attempt in order:**
1. Drop the `locales=` line from `alpenflight/auth/themes/alpenflight/email/theme.properties` (the parent `keycloak` already provides the full locale set; the override is redundant and likely the bug).
2. If (1) doesn't resolve: ship empty `email/messages/messages_{de,en,fr,it}.properties` files so the locale-supported declaration has companion bundles.
3. If (2) doesn't resolve: enable `quarkus.log.category."freemarker".level=DEBUG` (or `KC_LOG_LEVEL=DEBUG` scoped) on the Keycloak container, reproduce the verify-email send, capture the FTL line that fails. Raise to operator with the captured stacktrace.

Smoke verification: trigger a verify-email through `/signup` (local-signup flavor, locale `de` user — matches the failing case); mailpit (`http://localhost:8025`) receives the email; body rendering doesn't matter (parent inheritance handles brand inheritance per S-171); the absence of `SEND_VERIFY_EMAIL_ERROR` in `kc.log` is the success signal.

**AC scope updates (carried into implementation; tracked at finalize):**
- AC §"Optional CTA hiding" — dropped (out of scope).
- AC §"alpenflight-web baseUrl" footer.ftl bullet — refined to `${client.baseUrl}` exactly (no FreeMarker `!` fallback).
- AC §"alpenflight-web baseUrl" — add `normalize-realm-export.sh` re-injection of the baseUrl placeholder (mirror `DEV_CLIENT_SECRETS`).
- AC §"Smoke" — add a first-clone-no-`.env` boot smoke (boots, `/signup` renders, Google CTA visible, click → `invalid_client` expected).
- AC §"Verify-email boy-scout (NEW)" — fix the `Failed to template email` regression per the diagnostic plan above; smoke is "verify-email arrives in mailpit without `SEND_VERIFY_EMAIL_ERROR` in `kc.log`".

## Edge cases & hidden requirements

- **Keycloak `${env:VAR}` fails-hard if VAR is literally unset.** Compose's `${VAR:-default}` substitutes before the container sees the env, so the empty-`.env` path lands the placeholder string `set-via-env-for-google-signup` into Keycloak (resolved), not unset. Operator sees `invalid_client` from Google, not a Keycloak boot crash. The two layers compose cleanly.
- **`env_file:` does NOT feed compose's `${VAR:-default}` interpolation** — it's read at container start (process-env). Only the shell env + top-level `.env` at repo root feed interpolation. The dev defaults in `docker-compose.yml:204-216` still fire when the shell env is empty; the `.env` file then overrides at the container process layer. Intended interaction.
- **Secret rotation cache trap.** `docker compose up -d` alone does NOT re-evaluate `env_file` on a running container. Document `docker compose up -d --force-recreate keycloak` (cheap, preserves H2). `rebuild-keycloak.sh` is the heavier path (drops H2, wipes federated user accounts).
- **`client.baseUrl` also feeds Keycloak's account-console "back to application" link** (not only `footer.ftl`). Manual smoke must hit `/realms/alpenflight/account/` in addition to `/login`. README:194 already notes this.
- **First-clone diagnostic.** Fresh `git clone` → `dev-up-full.sh` with no `.env` must still boot (compose defaults cover it). Smoke: stack boots; `/signup` renders; Google CTA visible; click → Google `invalid_client` (expected fallback behavior, not a setup bug).
- **`.env.example` location.** Lives in `alpenflight/auth/` (next to `realm-export.json` + the operator runbook it parameterizes), NOT repo root.
- **`.env` syntax — bare `KEY=value`.** No `${...}` wrappers — docker treats `$` literally and would push gibberish into Keycloak. Document in `.env.example` header.

## Security plan

- **(1) `.env` accidental commit** — STRUCTURAL: `.gitignore` adds `alpenflight/auth/.env`. Beyond gitignore, no code-level guard against `git add -f`; operator-aware floor.
- **(2) Real-secret round-trip into `realm-export.json`** — STRUCTURAL: Google client id/secret already guarded by `check-realm-shape.sh:194-197` from S-134; do NOT duplicate. Story adds the *baseUrl* literal-leak guard at the same shape-guard shelf. Correctness-not-security (prod-misconfig blast radius), but the shape-guard is the right home.
- **(3) Open-redirect via hostile `ALPENFLIGHT_WEB_BASE_URL`** — OUT OF SCOPE. Env injection presumes the attacker already controls the deploy env; at that point Keycloak is gone. The shape-guard in (2) covers the committed-file path.
- **(4) Real secret pasted into README.md** — CONVENTIONAL (operator-aware). Example uses an obviously-fake placeholder (`123456789012-fake-dev-only.apps.googleusercontent.com`); section header explicitly warns "never paste your real client ID into this file." Regex on prose docs gives too many false positives.
- **(5) `.env.example` as social-engineering surface** — ACCEPTED. Same risk class as the docker-compose env-default pattern S-134 already ships; values are placeholder strings only.

**Production cutover.** Dev: per-laptop `.env` carries `KEYCLOAK_GOOGLE_*` + `ALPENFLIGHT_WEB_BASE_URL=http://localhost:4200/`. Prod: same keys set by deploy pipeline (not `.env`-file based); `ALPENFLIGHT_WEB_BASE_URL` pins to the real SPA origin. Realm-export contract unchanged.

## Test plan

**Parity strategy** — greenfield. No legacy oracle: `/Token` had no Google IdP, no env-substituted realm-export, no SPA-facing dev footer. Forward-only shape guards + operator smoke.

**New `check-realm-shape.sh` assertions:**
- `.clients[] | select(.clientId=="alpenflight-web") | .baseUrl` starts with `${env:ALPENFLIGHT_WEB_BASE_URL` and ends with `}` (anchors closing brace).
- Defensive companion: assert the field is non-empty so a future admin-UI export that drops `baseUrl` fails closed, not silently.

**Existing `signup.spec.ts` untouched** — CTA-hide is out of scope per grill.

**Manual smoke (README.md operator runbook):**
- `.env.example` → `.env` round-trip: copy, fill creds, `bash alpenflight/ops/dev-up-full.sh`, Keycloak boots, no `${env:...}` unresolved warnings in `kc.log`.
- `docker compose up -d --force-recreate keycloak` picks up rotation without dropping H2.
- "Back to Start" footer click lands on SPA root — both `ALPENFLIGHT_WEB_BASE_URL` set and unset (defaults-only) paths.
- First-clone-no-`.env` boot: stack up, `/signup` renders, Google CTA visible, click → Google `invalid_client` (expected).
- Account-console at `/realms/alpenflight/account/` — "back to application" link points at the SPA root.
- **Verify-email regression (boy-scout):** trigger `/signup` local flow → mailpit receives verification email; `kc.log` contains no `SEND_VERIFY_EMAIL_ERROR / Failed to template email`.
- `bash alpenflight/auth/scripts/check-realm-shape.sh` passes after manual edits.

**Out of scope** (explicitly not automated):
- Real Google OAuth round-trip against `accounts.google.com` — per-developer client, non-deterministic UA flow.
- Keycloak-rendered footer HTML scraping — no real-Keycloak Playwright project yet (S-021 follow-up).

## Performance plan

(N/A — devops/env-plumbing story; no queries, no hot paths, no indexes.)

<!-- modernize-refine: end -->
