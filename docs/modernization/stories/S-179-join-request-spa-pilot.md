---
id: S-179
title: Join-request SPA — pilot side (/join, /join/pending, post-signup landing flip)
epic: E-06
status: todo
depends_on: [S-178, S-176, S-021, S-134]
integration_base: integration/users-suite
acceptance:
  - **Post-signup landing flip.** The S-134 signup flow's default destination changes from `/migrate/start` to `/join`. Migration becomes a side path linked from `/join` ("Migrating from legacy FLS? Start here") + still reachable directly at `/migrate/start` for deep links. The `intent` query-param branches retained: `intent=migrate` → `/migrate/start`, `intent=join` → `/join`, default → `/join`. The `intent=demo` coercion to `/migrate/start` (S-134) becomes coercion to `/join` for the same reason (avoid open-redirect).
  - **/join page** (any authenticated principal without a `t_user` row): single form, one input — the join code (8 chars, auto-uppercased, monospace) + optional note textarea (capped 500 chars, character counter visible). "Request to join" button POSTs `/api/v1/join-requests`. On 201 → SPA navigates to `/join/pending`. On 404 (unknown code) → inline form error "Check the code with your club admin." On 409 (already attached) → friendly "You're already in {currentClubName}. Sign out to switch clubs." On 429 (rate-limit / cooldown) → countdown timer with the `Retry-After` value.
  - **/join/pending page.** Reads `GET /api/v1/me/join-request` on load. Shows: requested club's display name + city + logo (read from the public club projection — refine the public-readable shape with S-177), submitted-at timestamp, optional note echo, and a "Withdraw this request" link. While the page is open, the SPA subscribes to the SSE event kind `join-request.status-changed` (S-176). On `approved` → call the OIDC client's force-refresh → on new token (now carrying `clubId`) → navigate to `/start`. On `denied` → show the deny reason (if any) + "Try a different code" CTA → routes back to `/join`. On `withdrawn` → same `/join` redirect.
  - **Sign-out / multi-tab.** Tab A on `/join/pending`, Tab B signs out → both tabs end up at `/login`. (S-175's multi-tab logout harness is the regression witness.)
  - **/start guard.** Any user landing on `/start` (or any tenant-scoped route) without a `t_user` row + with a non-final `JoinRequest` is redirected to `/join/pending`. Without either, redirected to `/join`. This is the SPA-side gate that backs up the JIT 403 in S-169.
  - **Routing tests.** New Playwright spec `alpenflight/web/e2e/tests/join/join-request.spec.ts` covering: signup → /join → submit → /pending → SSE approve → /start; signup → /join → submit → deny → /join with friendly state; withdraw + re-submit; rate-limit toast; unknown-code error; the cross-tab logout case from above; the landing-flip default.
estimate: M
adr_refs: [0007, 0021]
---

## Context

Q6 + Q7 + Q16 grilling outcomes: signup defaults to /join (join is dominant signup path; migration becomes side door). Pending state has a friendly waiting page with withdraw + SSE-driven update. Q16's SSE choice over polling means this story's `MeEventsService.observe('join-request.status-changed')` subscription is the load-bearing piece.

## Cross-story contracts

- **Consumes:** S-178 REST surface (submit/withdraw/me-join-request); S-176 `MeEventsService` (SSE client); S-021 OIDC client force-refresh; S-134 signup intent routing.
- **Produces:** First end-to-end consumer of the SSE channel from the SPA — pattern reusable by S-180 (admin side) + future in-app inbox stories.

## Open design questions (for refine)

- **Force-refresh API on the Angular OIDC client.** Confirm the exact call (likely `OidcSecurityService.forceRefreshSession()` from `angular-auth-oidc-client`). Verify it works while the user is signed in but the token-refresh-flow doesn't bounce them through the login redirect.
- **Public club-projection shape.** /join/pending displays the requested club's name + city + logo. Today's `GET /api/v1/clubs/{id}` requires tenant. New endpoint `GET /api/v1/public/clubs/{id}` returns the bare public projection (name + city + logo URL + active flag) gated by the join-request row holding that `clubId` for the caller's sub. Refine confirms scope: this story owns the public projection too, or punts to S-180.
- **Login flow when user has no `t_user`.** After signup, the user has a KC session but no `t_user`. S-169's JIT requires `clubId` claim — none yet. Confirm `/api/v1/me` returns a degraded shape (`{ id: null, personId: null, clubId: null, … }`) per S-165, so the SPA can detect "needs onboarding" without 403-ing.
- **What if pilot just closes the tab while pending?** No SSE listener; admin approves; pilot opens app next day → SPA reads `/api/v1/me`, sees no clubId, reads `/me/join-request`, sees `status=approved` (acceptance 4 of S-178 — refine confirms approved rows remain accessible to the pilot post-decide, with a short retention window) → force-refresh → /start. Refine pins the retention window.
