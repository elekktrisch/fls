# 0015 — PWA offline-write architecture

- **Status:** Accepted
- **Date:** 2026-05-15
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)):
  2. Team-familiar stack
  5. Structural multi-tenancy supported (offline writes must NOT bypass the tenant guard)
  7. Solo-operator operability
  8. Enables fast feature dev post-cutover
  12. Supports the C17 end-user improvements within chosen stack

## Context

The [2026-05-15 vision amendment](../02-vision-and-constraints.md) introduces C17 (PWA with full offline writes ships pre-cutover), C18 (server-wins conflict resolution with a reconciliation prompt — no silent overwrites, no automatic merge), and the NFR "queued offline write reaches the server within 5 s of connection restored." Together they define the **mutation lifecycle when the client is offline**: queue locally, sync on reconnect, surface conflicts to the user.

The "log and walk away" use case at the airfield is the load-bearing scenario: a pilot logs a flight on a phone with no cell signal, locks the screen, and walks to the club bar. The write must survive both the offline window and tab-closure / screen-lock — the user is not coming back to a tab to trigger sync. That constraint is what pushes the architecture past a simple in-tab queue.

## Options considered

### Option A — Service Worker + IndexedDB + Background Sync API + Workbox helpers
- **Capabilities:** Service worker intercepts mutation requests via `fetch` event; on offline, persists the request payload + entity `version` token ([S-067](../stories/S-067-optimistic-concurrency.md)) into IndexedDB. On reconnect, the browser fires a `sync` event (or `periodicSync` for fallback); the SW replays queued mutations against the server with `If-Match: <version>` headers. Server returns 412 on conflict; SW marks the queued mutation as "needs reconciliation"; SPA shows a reconciliation prompt with the diff on next visibility. Workbox library wraps the SW boilerplate.
- **Fit to criteria:** criterion 2 ✓ (Angular 21 PWA story is first-class; Workbox is mature); criterion 5 ✓ (queued mutations carry the same auth bearer + tenant context as live ones, replayed unmodified); criterion 7 ✓ (no server-side queue infrastructure — Postgres + Spring Boot unchanged); criterion 8 ✓ (the conflict-resolution UX is reusable across all mutating routes); criterion 12 ✓ (fulfills C17 + C18 + NFR sync latency directly).
- **Migration cost:** medium. SW setup, IndexedDB schema, retry logic with backoff, reconciliation-prompt UX component. Bounded by published patterns.
- **Ecosystem risk:** medium. Safari's Background Sync API is partial — iOS Safari (16.4+) fires `sync` on visibility, not reliably in the background. Mitigation: manual `online` event listener as fallback; the < 5 s NFR is still met because users typically return to the app on reconnect.
- **Escape hatch:** drop offline writes; SPA falls back to online-only PWA. Reverting requires removing the SW `fetch` handler — non-destructive to other features.

### Option B — In-memory queue with manual `online` trigger (no service worker)
- **Capabilities:** SPA holds queued mutations in IndexedDB (persistent across reloads but NOT across tab closure on iOS) — the SPA itself drains the queue on `online` event. No service worker means no background processing.
- **Fit to criteria:** criterion 2 ✓; criterion 5 ✓ (same auth replay); criterion 7 ✓ (simpler — fewer moving parts than A); criterion 12 ✗ — **fails the "airfield log and walk away" use case**: if the user locks the phone or closes the tab while offline, the queue waits for an active tab to drain on reconnect.
- **Migration cost:** low.
- **Ecosystem risk:** low.
- **Escape hatch:** upgrade to A if the use case proves it's needed.

### Option C — Third-party offline-sync library (RxDB, AWS Amplify DataStore, PouchDB+CouchDB)
- **Capabilities:** the library handles queue + sync + conflict-resolution out of the box. Some sync to specific backends (Amplify → DynamoDB; PouchDB → CouchDB); others are backend-agnostic (RxDB) but need server adapters.
- **Fit to criteria:** criterion 2 ~ (Angular bindings exist but vary in maintenance); criterion 5 ~ (tenant context must be threaded manually through the library's conflict resolver); criterion 7 ✗ (adds a heavy dependency); criterion 8 ✗ (library upgrades become a separate concern); criterion 9 N/A (data migration is owned by S-016, not the offline-sync lib).
- **Migration cost:** medium — library learning + integration; possibly a backend adapter.
- **Ecosystem risk:** high — many libraries are tied to specific backends; vendor switch costs are real.
- **Escape hatch:** rip out the library; reimplement A. Non-trivial because the conflict-resolution semantics may have leaked into UI code.

## Decision

Chosen: **Option A — Service Worker + IndexedDB + Background Sync API + Workbox helpers**, with the entity `version` token from [S-067 (optimistic concurrency on Flight)](../stories/S-067-optimistic-concurrency.md) extended to every mutating entity that participates in the offline-write flow. Driven by criterion 12 (only option that fulfills the C17 "log and walk away" airfield use case), criterion 5 (offline writes replay unchanged through the same tenant guard as live writes), and criterion 7 (no new server-side infrastructure — Postgres + Spring Boot unchanged).

Conflict handling per C18: on server 412 (`If-Match` mismatch), the SW marks the queued mutation as "needs reconciliation" and the SPA presents the user with a side-by-side diff (server state vs. queued mutation) on next visibility. The user picks **keep server** (discard the queued change) or **retry with current server state** (re-edit the form against the latest server state). No three-way merge UI; no automatic field-level merging. This aligns with the operator's explicit pick of "server-wins with reconciliation prompt" over "last-write-wins" and "three-way merge UI."

Sync latency NFR (< 5 s after reconnect): the SW's `sync` event fires immediately on `online`; on browsers with limited Background Sync support (iOS Safari), a `window.addEventListener('online', ...)` triggers manual drain. Both paths share the same drain function.

## Consequences

- **Positive:**
  - Pilots can log flights from the airfield even with spotty connectivity; queued writes sync within 5 s of reconnect without requiring the user to come back to the app.
  - Conflict surface is explicit and user-driven, eliminating the silent-overwrite class of bugs that legacy doesn't protect against (legacy has no `@Version` column anywhere).
  - Reuses the optimistic-concurrency story (S-067) — every entity with offline writes needs a version column anyway, so the offline plumbing piggybacks on existing parity work.
  - Server-side burden is small: 412 responses + a reconciliation-friendly DTO shape. No queue table, no replay service, no scheduled retry job.
  - The SW also serves the PWA shell + cache (offline reads) for free.

- **Negative:**
  - Service worker development is notoriously footgun-prone — stale SW versions, scope confusion, race conditions between SW and app code. Mitigation: use Workbox; commit to a release procedure that bumps the SW version on every deploy.
  - iOS Safari's Background Sync limitations mean iOS users may need to return to the app on reconnect for sync to fire reliably (still well under the < 5 s NFR for the common case where the user re-opens the app after reconnect). Document in the user-facing help section.
  - Reconciliation UX has to be designed thoughtfully — too aggressive ("conflict!" toast on a trivial whitespace change) trains users to dismiss; too lax silently loses edits. Mitigation: only trigger reconciliation when the entity `version` differs, not on every save.
  - IndexedDB quota errors are possible on heavy queue accumulation. Mitigation: cap the queue to N pending mutations and surface a UX warning when approaching the limit.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** Extend [S-067](../stories/S-067-optimistic-concurrency.md) (`@Version` column on Flight) to every entity participating in offline writes — at minimum: `Flight`, `Reservation`, `Person` self-edits. Audit the C17 flow inventory to enumerate.
  - **Story:** Service worker bootstrap + Workbox integration in the Angular skeleton (touches [S-001](../stories/S-001-scaffold-server-skeleton.md) / [S-002](../stories/S-002-scaffold-web-skeleton.md) — likely a follow-up story rather than a precondition).
  - **Story:** IndexedDB queue schema + offline mutation-interceptor (touches the orval-generated API client wiring per S-004).
  - **Story:** Reconciliation-prompt UI component (`<fls-conflict-prompt>`) for the [S-008](../stories/S-008-component-primitives-kit.md) primitives kit.
  - **Story:** SW upgrade procedure + version-bump on deploy (touches [S-040](../stories/S-040-spring-dockerfile.md) build pipeline).
  - **Story:** Offline-write E2E test under Playwright (`offline` simulation in Playwright is supported; tests assert queue + drain + reconciliation flow end-to-end).
  - **NFR test:** Synthetic Playwright test that asserts < 5 s sync latency on reconnect (consumed by [S-111](../stories/S-111-perf-verification.md) performance verification).
  - **No new ADR** — implementation choices (Workbox version, IndexedDB schema specifics) are story-level.
