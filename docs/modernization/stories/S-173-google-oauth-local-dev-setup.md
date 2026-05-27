---
id: S-173
title: Keycloak operator-env plumbing — Google OAuth + alpenflight-web baseUrl
epic: E-03
status: todo
estimate: S
depends_on: [S-134, S-171]
integration_base: integration/users-suite
adr_refs: [0007]
refined: false
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
- Production uses a single hosted OAuth client; rotation goes through `ALPENFLIGHT_KEYCLOAK_GOOGLE_CLIENT_SECRET` at deploy. No change to the prod env contract.
- Existing `check-realm-shape.sh` already asserts the placeholders stay literal in the committed JSON — a leaked secret via sloppy `export-realm.sh` round-trip fails CI loudly. Don't loosen.
- The CTA-hide is *optional* but worth doing — landing on Keycloak's stock `invalid_client` page from the SPA looks broken to a fresh developer trying the stack out.
- `client.baseUrl` is the Keycloak-standard field for "the application's main URL" — it also feeds the "back to application" link in the account console, so setting it benefits more than just the login footer. The env-substitution pattern matches what S-134 already established for Google.
