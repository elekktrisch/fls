---
id: S-062c
title: Flight create/edit forms (glider + tow) + copy flow
epic: E-07
status: done
started_at: 2026-05-25
done_at: 2026-05-25
github_issue: 124
github_pr: 125
depends_on: [S-062a, S-062b, S-007, S-008]
acceptance:
  - `/flights/new`, `/flights/copy/:id`, `/flights/:id/edit` render a 3-step wizard (Launch / Glider / Tow). Step 3 is replaced by an empty-state when the start-type is not Aerotow.
  - Edit-load via `GET /flights/{id}` (+ follow-up GET for the linked tow row when `towFlightId` is set). Copy via `GET /flights/{id}/copy-template`. Both apply empty-UUID load normalization in the form-model load mapper.
  - Paired-create orchestration (`FlightStore.savePair`): `POST /flights` glider → `POST /flights` tow → `PUT /flights/{gliderId}` with `If-Match` to link `towFlightId`. Tow-POST failure triggers a compensating `DELETE /flights/{gliderId}` (best-effort; orphan GC is a backend concern). Update is a single PUT per row via `updatePair`.
  - `FlightFormCoordinator` (plain TS, no Angular DI) hosts the cross-field reactive rules from the parity oracle. Rules that key off rich master-data flags (solo tri-state, invoice clear, seat-count force-solo, route-required) are wired but await richer list-projection fields from the masterdata stores — see Follow-ups.
  - Submit-time glider→tow sync of `startDateTime`/`startLocationId`/`outboundRoute` runs **before** the tow-discard check in `flight-form.model.ts:snapshotToCreateRequests`; tow row dropped when `!needsTowplane(startTypeId) || !tow.aircraftId`.
  - `FlightPrefsService` (native IndexedDB, OIDC-`sub`-scoped) persists `lastStartLocation`, `lastTowAircraftId`, `towPilotByAircraftId`. Subscribes to `MUTATION_BUS` and drains on `session.logout` and `session.tenantSwitch`.
  - Smart defaults: `buildDefaultsForNew` overlays `new-template` → `last-context` (empty-fields-only) → `prefs.lastStartLocation`. Null `last-context` falls through silently.
  - FlightStore detail slice: `current`, `currentTow`, `currentVersion`, `currentTowVersion`, `loadDetail`, `loadNewTemplate`, `loadCopyTemplate`, `loadLastContext`, `savePair`, `updatePair`, `deleteOne`. Emits `flight.{created,updated,deleted}` on `MUTATION_BUS`.
  - First-pass keyboard: Tab order is natural; `Enter` advances the step / submits only on the last step; `Esc` opens the dirty-confirm `<af-dialog>` (a second `Esc` dismisses it). Ctrl+D / 1–5 / slide-in focus are deferred to S-062i.
  - 412 path: `If-Match` is plumbed on every PUT. A 412 surfaces a placeholder conflict signal via `FlightStore.hasSaveConflict`; the inline diff dialog ships in S-062h.
  - Ported parity specs `04-flights-create.spec.ts` + `05-flights-edit.spec.ts` mock the backend via `page.route`, walk the wizard, and assert the POST / PUT request shape + `If-Match` header. The legacy-stack SQL-and-UI-round-trip oracles run against the real backend in S-110 territory.
estimate: L
adr_refs: [0005, 0007, 0008]
parity_test: alpenflight/web/e2e/tests/flights/04-flights-create.spec.ts, alpenflight/web/e2e/tests/flights/05-flights-edit.spec.ts
refined: true
refined_at: 2026-05-25
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
---

## Context

Third of three sub-stories splitting the original S-062. The wizard MVP ships the happy-path: shell + paired-create + Copy-from-Last + smart defaults + first-pass keyboard. Resilience (drafts, inline 412 diff, marginal-3G UX) is owned by [S-062h](S-062h-flight-edit-resilience.md); keyboard polish (Ctrl+D, 1–5, slide-in focus) by [S-062i](S-062i-flight-edit-keyboard-polish.md).

## Implementation notes

- **`af-user-preferences-service` uses native IndexedDB instead of Dexie.** Same architectural intent (origin-scoped store, `sub`-keyed, session-lifecycle wipe-able); avoids a new dependency. Swap to Dexie when S-062h ships the drafts store and SW mutation queue if the API surface outgrows the thin wrapper.
- **Start-type IDs are hardcoded against the V2 seed.** `flight-start-types.ts` carries the four canonical start-type UUIDs from `V2__identity_and_reference.sql`; `needsTowplane()` matches against the Aerotow UUID. Replace with a `/start-types` reference-data endpoint + store consumption when one lands.
- **Route is `/flights/:id/edit` (not the legacy `/flights/:id`)** to keep `/flights/:id` free for the read-only detail view (S-105 / S-110). Edit-mode parity preserved.
- **The 3 wizard steps render a working subset of the legacy field surface** (date, start-type, start-location, aircraft, flight-type, pilot, times, landings, comment for glider; aircraft + pilot + landing time for tow). The remaining conditional controls (winch operator, instructor / co-pilot slots, engine counters, route inputs, no-time toggles, FCB / invoice / coupon) are tracked as scope add-ons for S-062h or a sibling story.

## Follow-ups

The MVP ships the structural slice + paired-create orchestration. Several refinement items survive as follow-ups — they need backend or sibling-story work to land:

- **Masterdata list-projection enrichment.** `AircraftListItem` lacks `nrOfSeats`; `FlightTypeListItem` lacks `isSoloFlight` / `isPassengerFlight` / `instructorRequired`; `LocationListItem` lacks `isOutboundRouteRequired` / `isInboundRouteRequired`. Until they ship, the wizard's `CoordinatorMetadata` adapter stubs those flags to neutral values and the corresponding cross-field rules (solo tri-state, invoice-recipient clear, seat-count force-solo, route-required) are wired but never fire. Belongs to the next masterdata-projection story (parent epic E-07 or a sibling refine).
- **`canUpdateRecord` / `canDeleteRecord` permission flags** are not yet on `FlightDetail` (S-062a gap). The wizard hardcodes both to `true` on load; whole-form disable for `Locked` / `DeliveryBooked` records will hook in when the backend surfaces them.
- **CDK Overlay + FocusTrap for `af-dialog`.** Current impl is a fixed-position div; per `web/CLAUDE.md` §5 organisms should use `@angular/cdk/overlay` + `@angular/cdk/a11y`. S-062h consumes the dialog for draft-restore + 412 diff and is the natural place to refactor.
- **Per-field "Copy from Last" buttons.** Persistence side is wired (`FlightPrefsService.update`); the UI affordance for explicit per-field copy is not. AC-DIR-4 will be re-surfaced in S-062h's resilience pass.
- **New Playwright specs `04b` (copy), `04c` (paired-create with tow-fail rollback), `04d` (keyboard-only), `04e` (mobile-wizard).** Deferred per operator scope decision; resilience-spec set `04f`/`04g`/`04h` already lives on S-062h.

## Client form mechanics

The verbatim legacy field table and cross-field reactive rules previously held here have been removed — the implementation in `alpenflight/web/src/app/features/flights/edit/flight-form.{model,coordinator,defaults}.ts` is now the authoritative source, and the legacy oracle remains accessible via the `flsweb/src/flights/FlightsController.js` and `FlightsServices.js` references the unit tests cite.
