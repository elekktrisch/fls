---
id: S-165
title: Home/dashboard page — greeting + last flight + quick actions (MVP, pilot variant) + /me endpoint
epic: E-07
status: todo
github_issue: 132
depends_on: [S-062a, S-062b, S-026]
acceptance:
  - New `GET /api/v1/me` endpoint returns the current user info `{id, personId, clubId, roles, firstName, lastName, email, username}` — resolved from JWT sub via existing `UserPrincipalLookup`. `personId` is nullable (sysadmins / unmapped federated users have none).
  - `GET /api/v1/flights` accepts `personId={uuid}` as a new filter — returns flights where any non-deleted FlightCrew row has `person_id` equal to the supplied uuid. Respects `@TenantId` on Flight (cross-tenant person ids harmlessly match nothing).
  - SessionStore loads `/me` on successful auth and exposes `personId` on the authenticated user.
  - Authenticated user navigating to `/start` sees a greeting (`Good {morning|afternoon|evening}, {given name}`) localized via Transloco + today's date in user-locale long format.
  - "Your last flight" card shows the most recent non-deleted Flight where the calling user's Person appears in ANY non-deleted FlightCrew role (PIC, co-pilot, instructor, student, tow pilot, winch operator, passenger), respects `@TenantId`, opens detail on click.
  - "Last" sort:  `flight_date DESC, start_date_time DESC NULLS LAST, created_on DESC`.
  - Includes flights in any process state (NotProcessed / Valid / Invalid / Locked / DeliveryBooked / ExcludedFromDeliveryProcess) — only `deleted_on IS NULL` filtered out.
  - Card body resolves location codes / aircraft immat / flight-type name from FE picker stores; the user's role (PIC, copilot, etc.) is computed FE-side by matching `crew[].personId` against `me.personId`.
  - Empty state when the user has no flights OR `me.personId` is null: card shows "No flights yet — log your first" + CTA to `/flights/new`. Same visual for both cases.
  - "Next reservation" card renders as a placeholder slot (header + "Reservations coming soon" body) — layout-stable so S-068's body swap doesn't shift the hero.
  - Quick-action buttons: "Open logbook" → `/flights` (secondary), "Log flight" → `/flights/new` (primary).
  - Top row stacks single-column at viewport < 900 px (`screens-home.jsx:249-261` breakpoint).
  - All roles (pilot, club-admin, sysadmin) see this same pilot-variant page in S-165; role-specific variants are follow-up stories S-166 (club-admin) and S-167 (sysadmin).
estimate: M
adr_refs: [0008]
parity_test: alpenflight/web/e2e/start/start.spec.ts (new) — auth + dashboard smoke + empty state
refined: true
refined_at: 2026-05-25
refined_specialists: [operator-grill]
---

## Context
First surface a logged-in user sees post-Keycloak. Legacy `flsweb/src/main/dashboard/` carried flight chart + safety gauge + my-stats + club aggregates; full port is deferred behind data dependencies. **MVP scope is the walking-skeleton home page** — proves the post-login surface works end-to-end with what's already in the schema. Replaces the existing placeholder at `alpenflight/web/src/app/features/start/start.page.ts`.

<!-- modernize-refine: start -->

## Design notes

**Route + auth.** Reuses the existing `/start` route + `authGuard` (`alpenflight/web/src/app/features/start/start.routes.ts`). The `tenantRequiredGuard` already redirects here on tenant-less sessions; that behavior is preserved.

**Identity resolution.** JWT sub → `user.keycloak_sub` → `user.id` → `user.person_id` (already plumbed in `UserPrincipalLookup`; the new `/me` endpoint exposes `personId` so the FE can use it as a general filter without server-side "me" magic). If `user.person_id` is null (sysadmin or unmapped federated user), `/me` returns `personId: null` and the FE skips the flight-list call entirely — empty state renders. Sysadmins will eventually see a different dashboard via S-167.

**New endpoint: `GET /api/v1/me`.** Returns `{id, personId, clubId, roles, firstName, lastName, email, username}`. Authz: any authenticated user; no `@PreAuthorize` beyond auth. Resolves `id` + `personId` + `clubId` from JWT sub via `UserPrincipalLookup`; firstName / lastName from the linked Person row when present, else from the JWT claim. Foundational utility — future profile / settings / view-as / permission stories all consume it.

**Backend filter: `personId={uuid}`, not `mine=true`.** Extend `GET /api/v1/flights` with `personId={uuid}` query param. Server-side join: `WHERE EXISTS (SELECT 1 FROM flight_crew fc WHERE fc.flight_id = f.id AND fc.person_id = :personId AND fc.deleted_on IS NULL)`. Sort respects the AC. Pagination invariant (keyset cursor) carries through unchanged. `@TenantId` on Flight already guarantees cross-tenant person ids can't surface anything. Card flow: `/me` (cached once at auth) → `/flights?personId=<me.personId>&limit=1` → `/flights/{id}` for the rich detail. Generalises to "admin views pilot X's flights" without code change.

**Card projection.** No new DTO. FE consumes the existing `FlightDetail` and resolves names client-side from picker stores already cached for the form (`AircraftStore`, `LocationStore`, `FlightTypeStore`, `PersonStore`). The user's role in the flight is computed FE-side: find `crew[i]` where `personId === me.personId`; take the first match's `flightCrewTypeId` and look up the localized label.

**Greeting.** Time-of-day cutoffs at the implementer's discretion (suggested: < 12 morning, < 18 afternoon, else evening — culturally fine across DE/EN/FR/IT). Translation keys live under `home.greeting.morning/afternoon/evening`. Name fallback chain: `firstName || username` (matches the existing placeholder).

**Date format.** Angular `DatePipe` with the user's resolved locale via Transloco. CH-DE default per `lang-resolver.ts:5`.

**Next-reservation card.** Placeholder slot now; S-068's body swap targets this slot. Surface a one-line copy via a translation key (`home.reservations.placeholder`); structural shape stays.

## Cross-story contracts

- **Consumes:** `S-062a` (FlightDetail), `S-062b` (list endpoint shape — `personId` filter is additive), `S-026` (authz).
- **Produces (for follow-ups):**
  - `GET /api/v1/me` — consumed by **S-166** + **S-167** for role-based variant routing, and by any future profile / settings / view-as story.
  - `personId` filter on `/flights` — consumed by future "admin views pilot X's logbook" / "view-as" / per-person stats stories.
  - **S-166** (club-admin variant) — same `/start` route, role-switched component.
  - **S-167** (sysadmin variant) — same.
  - **S-068** (reservations) — fills the placeholder card.
  - Future stats / license / METAR / activity-feed stories — extend below the hero.

## Out of scope (filed as follow-ups)

- **S-166** Club-admin dashboard variant (TBD design).
- **S-167** Sysadmin dashboard variant (TBD design).
- Stats tiles, full statistics page, license/medical card, safety gauge, METAR, recent-activity feed — each becomes its own story once data deps exist.

## Edge cases & hidden requirements

- **`/me.personId` is null.** FE skips the flights call entirely; renders empty state directly. Don't log a warn on every page load.
- **`personId` filter with a uuid not in the caller's tenant.** Filter matches nothing (FlightCrew is on a tenant-scoped Flight); harmless, no IDOR — never returns a 403, just empty. Document this as a deliberate "no information disclosure" property.
- **`personId` filter with a malformed uuid.** Return 400 with the standard ProblemDetail shape, like any other validation failure.
- **User's club has zero flights.** Same empty state as "user has no flights."
- **Flight in `DeliveryBooked` is the user's latest.** Render normally — operator may want to see the locked card; click still opens detail (which itself enforces edit gates).
- **User appears only as `passenger` on their latest.** Still shows. Role label says "Passenger" so the user isn't surprised.
- **Multiple flights on same `flight_date` with no `start_date_time`.** Sort falls through to `created_on`. Acceptable.
- **Soft-deleted FlightCrew row for the user but Flight itself alive.** That flight is NOT "my last" — the join filters `fc.deleted_on IS NULL`. (Handles the case where the user was removed from a flight's crew after the fact.)
- **`/me` called before any role / club / person resolution finished.** The endpoint runs in the request thread; `UserPrincipalLookup` is synchronous; no async race. If `keycloak_sub` lookup misses, `personId` / `clubId` return null and the FE renders the empty state.
- **No locale resolved.** Fall back to `de` per `DEFAULT_LOCALE`.

## Security plan

- **`/me` exposes only claims-derived data + the tenant-scoped person mapping.** No secrets, no tokens. Idempotent read; no audit row (audit is for mutations).
- **`personId` filter is information-flow-safe.** Because the filter applies on top of the `@TenantId`-scoped Flight query, supplying a personId from another tenant matches zero rows — it cannot reveal whether that person exists in any tenant. No 403; just empty list. This is the deliberate IDOR-as-404 contract.
- Within the caller's own tenant, the personId filter does NOT add a new authz boundary — flights are already visible across the club per legacy convention. (When that convention changes, the gate goes on the list endpoint as a whole, not specifically on this filter.)
- Audit: this story adds no mutation paths; AuditTrail unchanged.

## Test plan

**Backend (integration, Spring + Testcontainers).**
- `GET /api/v1/me` returns `personId` resolved via `UserPrincipalLookup`; `personId: null` when `user.person_id` is null.
- `GET /api/v1/me` for a JWT whose sub doesn't match any active user row returns null fields (id / personId / clubId) but firstName / lastName / email from JWT claim.
- `personId={uuid}` returns flights where that person is in any non-deleted crew role.
- `personId` filter honors `@TenantId` (other-club person id matches nothing; no 403).
- `personId` filter excludes flights where the user's FlightCrew row is soft-deleted.
- `personId={uuid}&limit=1` with the AC-specified sort returns the expected row.
- `personId` filter with a malformed uuid returns 400 ProblemDetail.
- `personId` filter includes flights in non-terminal process states.

**Frontend (vitest — logic only per [[feedback-fe-tests-unit-for-logic-playwright-for-dom]]).**
- Greeting selector picks morning/afternoon/evening by clock; falls back to morning on undefined locale time.
- Role-from-crew computation: matches by personId; returns localized label key.
- Picker-store resolution: name fallback when a picker store hasn't loaded yet (render "—" rather than blank).

**Playwright (`e2e/start/start.spec.ts`, new).**
- Smoke: pilot logs in → lands on `/start` → sees greeting + last-flight card with their data.
- Empty state: pilot with no flights → card shows the empty CTA.
- Quick actions navigate to `/flights` and `/flights/new`.
- Per [[feedback-e2e-screenshots-for-visual-verification]], write `screenshots/start/<state>.png` for the populated + empty states.

**Parity oracle.** None — legacy `flsweb/src/main/dashboard/` is being intentionally diverged (vision §F: simpler home; stats split out). No oracle file diff to gate.

## Performance plan
(N/A at MVP scale. The `mine=true` JOIN can ride the existing `ix_flight_crew_person_type` index on `(person_id, flight_crew_type_id) INCLUDE (flight_id)` per V3. Two-call pattern is one list-row read + one detail read per dashboard load.)

<!-- modernize-refine: end -->
