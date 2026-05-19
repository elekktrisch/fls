---
id: S-153
title: S-019 rework follow-ups (check-realm-shape + export-realm + misc hardening)
epic: E-03
status: todo
estimate: S
depends_on: [S-019]
origin: rework
origin_story: S-019
---

## Context

Catch-all for the 13 deferred improvements from `/modernize-review S-019` (PR #60). All are 1-5 line edits across `alpenflight/auth/scripts/`, `alpenflight/auth/README.md`, and `docker-compose.yml`. The blocker + 4 critical improvements + ADR 0007 amendment landed in S-019's PR; this story groups the rest into one ship.

The full review block (including `[deferred → S-153]` annotations) is in `docs/modernization/stories/implemented/S-019-keycloak-compose.md`.

## Acceptance criteria

`check-realm-shape.sh` gains:
- [ ] Proffix dev-secret value pinned (`alpenflight-proffix.secret == "alpenflight-proffix-dev-secret"`).
- [ ] `webOrigins == ["+"]` plus `redirectUris` all start with `http://localhost:` (so a future redirect addition can't widen CORS implicitly).
- [ ] Redirect-URI guard rejects `*` anywhere in a URI, not just exact-equals.
- [ ] PII regex matches `@*.test` (not just `@test`).
- [ ] Comment on the `registrationAllowed=false` assertion explaining it's current-dev-only; S-134 self-service signup will need a conditional or separate guard.

`export-realm.sh` gains:
- [ ] Cached per-user role-mapping lookup (iterate `jq -r '.[] | "\(.username) \(.id)"'` once over `users.json` instead of re-fetching IDs).
- [ ] Single-quoted trap body (`trap 'rm -rf "$WORK"' EXIT`).
- [ ] Word-split-safe iteration (`while IFS= read -r u; do … done < <(jq -r …)`).
- [ ] Admin token revoke / `unset TOKEN` in cleanup trap.

`normalize-realm-export.sh` gains:
- [ ] Python lifted out of the bash heredoc into `alpenflight/auth/scripts/normalize-realm-export.py` (or called directly from `export-realm.sh`).
- [ ] Bare `open()` calls replaced with context managers.

Docs/ops touches:
- [ ] `alpenflight/auth/README.md` Downstream consumers table includes S-134 (federated IdP + DB-fallback for `clubId`).
- [ ] `docker-compose.yml:206-208` healthcheck carries a one-line comment documenting the bash `/dev/tcp` dependency.
- [ ] `alpenflight/auth/scripts/check-realm-shape.sh` clubId-block trailing-clause restates the `fail` message — trim to keep only the why-explanation.
- [ ] `alpenflight/auth/scripts/check-realm-shape.sh` token-policy block comment paraphrases its section header — drop the comment.
- [ ] `alpenflight/auth/README.md` round-trip code-block comment is 3 lines at shell indent — condense to one line.
