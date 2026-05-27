---
id: S-173
title: Google OAuth IdP — usable for local dev (per-developer client)
epic: E-03
status: todo
estimate: S
depends_on: [S-134]
integration_base: integration/users-suite
adr_refs: [0007]
refined: false
origin: punch-list
---

## Context

S-134 wired Google as an identity provider in the alpenflight realm-export with env-substituted `clientId` / `clientSecret` placeholders. Operationally, clicking "Continue with Google" on the login page surfaces Keycloak's `invalid_client` page until those env vars are set — because no developer has been through the Google Cloud Console one-time setup, and there's no documented `.env` workflow.

Google does support `http://localhost` (and `127.0.0.1`) as both an OAuth redirect URI and a JavaScript origin without requiring HTTPS — explicitly as a development carve-out. So a per-developer OAuth client *can* be created against the local Keycloak; what's missing is the convention.

This story makes the local Google flow opt-in but trivial to enable.

## Acceptance criteria

### Operator setup

- [ ] `alpenflight/auth/.env.example` exists with all `KEYCLOAK_GOOGLE_*` (+ SMTP override) keys documented inline, with the expected Google Cloud Console redirect URI written verbatim.
- [ ] `alpenflight/auth/.env` is git-ignored (project root `.gitignore` extended).
- [ ] `docker-compose.yml`'s `keycloak` block reads `env_file: alpenflight/auth/.env` (or equivalent) so the operator's `.env` flows into the container without manual export.
- [ ] `alpenflight/auth/README.md` § "Google Cloud Console — one-time setup" expanded:
  - exact click-path through Google Cloud Console
  - exact redirect URI string (`http://localhost:8090/realms/alpenflight/broker/google/endpoint`)
  - exact JS origin (`http://localhost:8090`)
  - note: "use a test Gmail account; this consumes one of your free OAuth clients but is throwaway"
  - what to do when the consent screen blocks ("publish to test users" mode + add your own email)

### Optional CTA hiding (only if env-unset)

- [ ] When `KEYCLOAK_GOOGLE_CLIENT_ID` is unset or equals the literal `set-via-env-for-google-signup`, the SPA's `/signup` route hides the "Continue with Google" CTA instead of letting the user click into a Keycloak error page. (Implementation hook: surface a `google_idp_enabled` boolean from a backend `/api/v1/config` endpoint, or a build-time env. Pick the cheaper path during refinement.)
- [ ] Existing `signup.spec.ts` updated to cover both states (Google CTA visible / hidden).

### Smoke

- [ ] Fresh-clone walkthrough: copy `.env.example` → `.env`, fill creds from Google Cloud Console, `bash alpenflight/ops/dev-up-alpenflight.sh`, click "Continue with Google" on `/signup` → consent screen → land on Keycloak's first-broker-login → verify-email via Mailpit → SPA receives session.
- [ ] `bash alpenflight/auth/scripts/check-realm-shape.sh` still passes (the env placeholders in `realm-export.json` are unchanged; the secret never lands in the committed file).

## Tasks

- [ ] Write `alpenflight/auth/.env.example`.
- [ ] Extend `.gitignore`.
- [ ] Wire `env_file` on the keycloak service.
- [ ] Doc updates in `alpenflight/auth/README.md`.
- [ ] (Optional, decide in refinement) Backend `/api/v1/config.googleIdpEnabled` + SPA conditional render of the Google CTA.

## Notes

- Per-developer Google client: yes, every developer needs their own. Google explicitly forbids sharing OAuth clients across developers; the secret is a per-app secret. The `.env` convention keeps secrets per-laptop.
- Production uses a single hosted OAuth client; rotation goes through `ALPENFLIGHT_KEYCLOAK_GOOGLE_CLIENT_SECRET` at deploy. No change to the prod env contract.
- Existing `check-realm-shape.sh` already asserts the placeholders stay literal in the committed JSON — a leaked secret via sloppy `export-realm.sh` round-trip fails CI loudly. Don't loosen.
- The CTA-hide is *optional* but worth doing — landing on Keycloak's stock `invalid_client` page from the SPA looks broken to a fresh developer trying the stack out.
