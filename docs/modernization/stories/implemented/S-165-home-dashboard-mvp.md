---
id: S-165
title: Home/dashboard page — greeting + last flight + quick actions (MVP, pilot variant) + /me endpoint
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
github_issue: 132
github_pr: 133
depends_on: [S-062a, S-062b, S-026]
acceptance:
  - New `GET /api/v1/me` endpoint returns the current user info `{id, personId, clubId, roles, firstName, lastName, email, username}` — resolved from JWT sub via existing `UserPrincipalLookup`. `personId` is nullable (sysadmins / unmapped federated users have none).
  - `GET /api/v1/flights` accepts `personId={uuid}` as a new filter — returns flights where any non-deleted FlightCrew row has `person_id` equal to the supplied uuid. Respects `@TenantId` on Flight (cross-tenant person ids harmlessly match nothing).
  - SessionStore loads `/me` on successful auth and exposes `personId` on the authenticated user.
  - Authenticated user navigating to `/start` sees a greeting (`Good {morning|afternoon|evening}, {given name}`) localized via Transloco + today's date in user-locale long format.
  - "Your last flight" card shows the most recent non-deleted Flight where the calling user's Person appears in ANY non-deleted FlightCrew role (PIC, co-pilot, instructor, passenger, winch operator, observer, flight-cost-invoice recipient), respects `@TenantId`, opens detail on click.
  - "Last" sort:  `flight_date DESC, start_date_time DESC NULLS LAST, created_on DESC`.
  - Includes flights in any process state (NotProcessed / Valid / Invalid / Locked / DeliveryBooked / ExcludedFromDeliveryProcess) — only `deleted_on IS NULL` filtered out.
  - Card body resolves location codes / aircraft immat / flight-type name from FE picker stores; the user's role is computed FE-side by matching `crew[].personId` against `me.personId`.
  - Empty state when the user has no flights OR `me.personId` is null: card shows "No flights yet — log your first" + CTA to `/flights/new`. Same visual for both cases.
  - "Next reservation" card renders as a placeholder slot — layout-stable so S-068's body swap doesn't shift the hero.
  - Quick-action buttons: "Open logbook" → `/flights` (secondary), "Log flight" → `/flights/new` (primary).
  - Top row stacks single-column at viewport < 900 px.
  - All roles (pilot, club-admin, sysadmin) see this same pilot-variant page in S-165; role-specific variants are follow-up stories S-166 (club-admin) and S-167 (sysadmin).
estimate: M
adr_refs: [0008]
parity_test: alpenflight/web/e2e/tests/start/start.spec.ts — auth + dashboard smoke + empty state
refined: true
refined_at: 2026-05-25
refined_specialists: [operator-grill]
---

## Context
First surface a logged-in user sees post-Keycloak. The legacy dashboard
(flight chart, safety gauge, my-stats, club aggregates) is being
intentionally diverged per vision §F; this MVP is the walking-skeleton
home page that proves the post-login surface works end-to-end with what's
already in the schema. Replaces the prior placeholder at `/start`.

## Cross-story contracts

- **Consumes:** S-062a (FlightDetail), S-062b (list endpoint shape — `personId` filter is additive), S-026 (authz).
- **Produces (load-bearing for follow-ups):**
  - `GET /api/v1/me` — consumed by S-166 + S-167 for role-based variant routing, and by any future profile / settings / view-as story.
  - `personId` filter on `/flights` — consumed by future "admin views pilot X's logbook" / view-as / per-person stats stories. Cursor pagination is best-effort within same-day ties when `personId` is supplied; S-165 only consumes `limit=1` so a future view-as story may need a richer cursor.
  - S-068 (reservations) fills the placeholder card.
  - S-166 / S-167 are same `/start` route, role-switched component.

## Parity exclusions

- No legacy oracle — `flsweb/src/main/dashboard/` is intentionally not ported; vision §F reduced the home surface.
- Stats / license / METAR / activity feed / safety gauge are all out of scope here; each becomes its own story once data deps exist.

## Post-mark-done additions

After the reviewer panel signed off and the story was marked done, two
small in-scope wirings landed to thread the new home dashboard into the
post-login + chrome surfaces — both are coherent extensions of "if `/start`
IS the home, that's where you arrive + where the brand link points":

- `AppComponent` passes `brandHref="/start"` to `<af-nav-bar>` so clicking
  the logo / wordmark in the top bar navigates to the home dashboard.
- `DEFAULT_POST_LOGIN_ROUTE` (in `core/auth/post-login-redirect.ts`) flips
  `/clubs` → `/start`; deep-link redirects (`consumePostLoginRedirect()`)
  still win when set, falling back to `/start` otherwise. `core/auth/README.md`
  flow diagrams updated to match.

## Open design questions (deferred to operator at follow-up)

- `KNOWN_REALM_ROLES` in `MeService` mirrors the FE `AppRole` union and `alpenflight/auth/realm-export.json` — three hand-maintained copies of the same vocabulary. A CI cross-check (or codegen from realm-export) is a follow-up.
- `MeService.SELECT_USER_AND_PERSON` and `UserPrincipalLookup` share the `keycloak_sub`-lookup predicate; consolidation is a follow-up structural refactor.
- The cursor-pagination caveat on `/flights?personId=…` is unblocked until a paginated view-as story lands.
