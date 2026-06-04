---
id: J-3
title: Dashboard / home — role variants + live updates (/start)
epic: E-07
status: todo
journey0: false
carved: true
depends_on: [J-2]
rolls_up: [S-176, S-166, S-167]   # S-165 (pilot MVP + /me) already done+merged — reused, not rolled up
acceptance:
  - Pilot logs in → `/start` renders the pilot variant: localized greeting + "your last flight" card + quick actions (S-165 baseline, re-asserted green). [happy]
  - A CLUB_ADMINISTRATOR sees the club-admin variant at the same `/start` route — admin tiles (today's club flights + a flights-pending-validation count) instead of / alongside the pilot hero; a "Pilot view" toggle falls back to the pilot variant. [happy]
  - A SYSTEM_ADMINISTRATOR sees the sysadmin variant — cross-tenant tiles (total clubs / users / flights) + an entry point to switch into a tenant. [happy]
  - SSE live update: with the dashboard open, a server-published event over `GET /api/v1/me/events` updates a dashboard tile **without a reload** (e.g. a `flight.created` event refreshes the pilot's last-flight card / the admin's today-count). [happy]
  - SSE auth: `/api/v1/me/events` requires a valid Bearer token — an anonymous / no-token connection is rejected (no anonymous stream). [key-error]
  - Tenant scope: admin tiles count only the caller's own club; the sysadmin variant aggregates across clubs; a pilot never sees admin/sysadmin tiles (role-gated FE + tenant-gated counts). [key-error]
  - SSE resilience: an idle stream stays open across the heartbeat interval; the client transparently reconnects after a transient drop and resumes receiving events. [edge]
screen: /start   # replacing legacy main/dashboard/ — intentionally diverged (vision §F); legacy stats surfaces NOT ported
headless_pulled_in: SSE channel (MePrincipalEventBus + GET /api/v1/me/events, S-176) — homed on this screen as its first product consumer (the live-updating dashboard tile)
migration: N/A — greenfield (legacy dashboard intentionally not ported; no mapper, AlpenFlight-only gallery)
parity_test: alpenflight/web/e2e/tests/start/start.spec.ts   # extend the existing S-165 smoke spec
adr_refs: [0007, 0008, 0017, 0011]
---

## Context

`/start` is the first surface a user sees after Keycloak login. S-165 shipped the
walking-skeleton **pilot** variant (greeting + last-flight card + quick actions +
`GET /api/v1/me`) and it's merged. J-3 completes the screen: **role-switched variants**
(club-admin S-166, sysadmin S-167) on the same route, and the **live-update channel**
(S-176 SSE) that makes a dashboard tile refresh in place without a poll or reload — the
foundational transport later consumed by the in-app inbox, join-request approvals (J-12),
freemium prompts, and reservation alerts. The legacy dashboard (flight chart, safety
gauge, license/medical expiry, club aggregates) is **deliberately not ported** (vision §F,
recorded in S-165) — J-3 does not resurrect it.

## Spec must assert

The Playwright run (extending `start.spec.ts`, real-idp) proves, end to end:

1. **Pilot variant (S-165 re-assert):** login → `/start` → greeting (`Good {morning|afternoon|evening}, {name}`) + last-flight card resolves a real migrated/seeded flight + quick actions route to `/flights` and `/flights/new`. Empty state when the user has no flights or no `personId`.
2. **Club-admin variant (S-166):** a `CLUB_ADMINISTRATOR` principal at `/start` renders admin tiles — **today's club flight count** and **flights-pending-validation count** (`NotProcessed` + `Invalid`), both scoped to the caller's club; the "Pilot view" toggle swaps to the pilot variant. (MVP tile set — see Assumptions.)
3. **Sysadmin variant (S-167):** a `SYSTEM_ADMINISTRATOR` principal renders cross-tenant tiles — total clubs / users / flights — and a control that enters a tenant context. (MVP tile set — see Assumptions.)
4. **Live update (S-176):** dashboard open in one context; a `flight.created` event is published to the principal's channel (by creating a flight in a second context / via API); the relevant tile updates **without reload** within a few seconds. Assert the DOM changes, not a network poll.
5. **SSE Bearer auth:** `GET /api/v1/me/events` with no/invalid token → rejected (401, no stream). With a valid token → `200 text/event-stream` and the first event arrives.
6. **Tenant + role gating:** admin counts match only the caller's club (cross-club flights excluded); the pilot principal sees no admin tiles; the sysadmin aggregate spans clubs.

Heartbeat + reconnect (AC7) is asserted at the IT layer where a fixture timer is controllable; the e2e proves the happy live-update + auth reject.

## Notes

**Migration: none.** Greenfield screen — no legacy mapper, no fanout run required; the
done-bar demonstrability is the **AlpenFlight pass video + screenshots** in the gallery
(no paired legacy form — the legacy dashboard is an intentional non-port). Surface that
gallery early per do-ship §3.

**SSE transport — load-bearing carve decisions (ship-time confirms with operator):**
- **Server stack:** AlpenFlight is a **Servlet-stack** Spring modulith. Carve assumption:
  implement the stream with Spring MVC **`SseEmitter`** (Servlet 3 async) + a plain
  in-memory `MePrincipalEventBus` (a `ConcurrentMap<kcSub, Set<SseEmitter>>`), **not**
  WebFlux/`Flux` — avoid dragging a reactive runtime into one endpoint. S-176's task
  wording mentions Flux; this carve overrides toward the in-stack `SseEmitter` path.
  Pin at refine; if a strong reason to pull WebFlux emerges, **escalate for an ADR**
  (don't pull a runtime silently).
- **Browser auth:** native `EventSource` can't set `Authorization`. Carve assumption:
  **`@microsoft/fetch-event-source`** (fetch-stream reader) so the existing Bearer
  interceptor path carries the token — over the one-shot `?ticket=` query-param
  alternative. Pin the polyfill at refine.
- **Demonstrable event:** `flight.created` published to the creating principal's sub is
  the thinnest concrete consumer that proves the channel **without** waiting on S-178/J-12
  (whose `join-request.status-changed` is the eventual first real event). Publish from the
  flight-create application service via `MePrincipalEventBus.publish(sub, "flight.created", …)`.
- In-memory only, no replay across restart; every tile must also load its state via a
  normal GET (SSE is the change overlay, not the source of truth) — so the dashboard is
  correct on first paint and SSE only animates subsequent changes.

**Boyscout riders to fold (this journey runs the gate + touches e2e/CI/gallery):**
- **modernize-\* sunset** — **trigger now met** (J-2 was the first non-migration feature
  journey; this is the second). Delete the 9 `modernize-*` skills + ~12 agents + prune
  `rolled_up_into:` stories (~21 files, mechanical). Good fit to ride J-3.
- **"Run Playwright" → required `ci` gate** — structural CI fix (operator, J-2 retro);
  this journey's done-bar leans on the mock-auth e2e, so wire it required here.
- **Collapse the two proof galleries into one** — drop the ci.yml AlpenFlight-only gallery,
  keep the full one; greenfield J-3 is AlpenFlight-only so it's a clean place to do it.
- **e2e tsc-strictness + prettier-glob** — J-3 edits `start.spec.ts`; fold the e2e
  format/tsc normalization if it stays bounded.
- **Role-vocab single-source** (S-165 open q): `KNOWN_REALM_ROLES` (BE `MeService`) ↔ FE
  `AppRole` ↔ `realm-export.json` are three hand-maintained copies — J-3's role-variant
  routing reads exactly this vocabulary, so a CI cross-check rides naturally here.

**Likely task seams (non-binding, for /do-ship):** the `/start` role-switch shell
(routes off `me.roles`); the club-admin variant component + its tiles; the sysadmin
variant component + its tiles; the admin **count endpoints** (today's flights +
pending-validation, tenant-scoped — one resource cluster); the sysadmin **cross-tenant
aggregate** endpoint (one resource); `MePrincipalEventBus` + `MeEventsController`
(`GET /api/v1/me/events`, one backend seam); the `MeEventsService` Angular client (one
service); the `flight.created` publish wired into the flight-create service (one
publish-point); the `start.spec.ts` thickening (one spec).

## Assumptions made

- **Route is `/start`**, not the roadmap's stale `/dashboard` (S-165 shipped `/start`).
- **Club-admin MVP tiles** = today's club flight count + flights-pending-validation count;
  **sysadmin MVP tiles** = total clubs/users/flights + tenant entry point. Both stories say
  "scope TBD at refine" — these are the thinnest provable picks from their candidate lists;
  refine/operator may swap tiles without changing the journey shape.
- **SSE first event = `flight.created`** (see above) — chosen so J-3 doesn't depend on the
  unbuilt join-request slice.
- Heartbeat 25s + browser-default EventSource reconnect backoff (S-176 defaults), per-sub
  connection cap 8.
