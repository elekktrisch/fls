---
id: S-062c
title: Flight create/edit forms (glider + tow) + copy flow
epic: E-07
status: todo
depends_on: [S-062a, S-062b, S-007]
acceptance:
  - `/flights/new` renders a typed Reactive Form shell with the glider sub-form always present and the tow sub-form conditionally rendered when `startType === Towing` (parity with legacy `FlightsController.js:418-420, 666-673`).
  - `/flights/:id` loads an existing flight via `GET /flights/{id}` (S-062a) and patches the form; respects server-supplied `canUpdateRecord` / `canDeleteRecord` flags (whole form disables when `!canUpdateRecord`).
  - `/flights/copy/:id` fetches `GET /flights/{id}/copy-template` (S-062a), patches the form with the cleared draft, navigates to `/flights/new`'s save flow.
  - Save flow: glider+tow paired-create lands in ONE backend call (`POST /flights` with both blocks); update is one PUT with both blocks. Server (S-062a) handles the dual-row tx.
  - `FlightFormCoordinator` implements the cross-field reactive rules (full table in Client form mechanics): start-location mirror glider→tow, start-time mirror, outbound-route mirror, solo-flight derivation, co-pilot clear on solo, invoice recipient clear when not required, aircraft change resets engine counters, location change recomputes route requirement.
  - `prepareForSaving` mirror: glider→tow sync of `startDateTime`/`startLocationId`/`outboundRoute`; tow data dropped when `!needsTowplane || !tow.aircraftId` (parity with `FlightsController.js:348-378`).
  - localStorage hydration (workstation-level UX): `lastStartLocation` auto-hydrated as default; `lastTowAircraftId`, `towPilotByAircraftId`, `lastGliderOutbound/Inbound`, `lastTowOutbound/Inbound` available via explicit "copy from last" buttons.
  - Parity specs `04-flights-create.spec.ts` and `05-flights-edit.spec.ts` green on the new stack with byte-identical assertions (selector adaptation via `data-testid` allowed; behavior assertions not).
  - New e2e specs added: `04b-flights-copy.spec.ts` (copy flow) and `04c-flights-paired-create.spec.ts` (glider+tow paired-create in a single submit).
  - FlightStore extended with detail-state slice (`current`, `currentVersion`, `save`, `delete`) and emits `MutationBus.flightChanged$` on save/delete (consumed by S-062b's list refetch).
  - On `412 Precondition Failed` (S-067 future): show a non-blocking "this flight was just edited" toast with a refresh action. Don't retry blindly.
estimate: M
adr_refs: [0005, 0007, 0008]
parity_test: tests/flights/04-flights-create.spec.ts, tests/flights/05-flights-edit.spec.ts
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer, frontend-form-engineer]
split_from: S-062
---

## Context

Third of three sub-stories splitting the original S-062 (see [S-062a](S-062a-flight-crud-backend.md) and [S-062b](S-062b-flight-list-page.md)). The biggest UI piece. Two separate forms (glider, tow) sharing a coordinator and a shell, plus the copy flow. The reactive-form field rules are dense — see the Client form mechanics section.

Specs `04-flights-create.spec.ts` and `05-flights-edit.spec.ts` are the parity oracles; they don't go green until this story does. New e2e specs `04b` (copy) and `04c` (paired-create) are added here to cover behavior the legacy specs skip.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Replace placeholder routes from S-062b: `/flights/new`, `/flights/:id`, `/flights/copy/:id` now render the edit shell.
- [ ] `flight-edit.component.ts` — shell: owns route params, masterdata loading, save/cancel, dispatch into either glider+tow or motor route (motor is S-064 — for now redirect motor flights there).
- [ ] `glider-flight-form.component.ts` + template — port of `flight-edit-glider-form.html` field-by-field.
- [ ] `tow-flight-form.component.ts` + template — port of `flight-edit-tow-form.html`.
- [ ] `flight-form.model.ts` — typed Reactive Forms model + `buildFlightForm()` factory.
- [ ] `flight-form-coordinator.ts` — plain TS class implementing all `valueChanges` cross-field rules from Client form mechanics. No Angular DI.
- [ ] `flight-form-defaults.ts` — `initForNewFlight` port: pulls `myClub.DefaultStartType / HomebaseId / DefaultGliderFlightTypeId / DefaultTowFlightTypeId` and writes form defaults.
- [ ] `flight-form-coordinator.spec.ts` — the cross-field rule table is testable in isolation (no Angular Testing Library needed for a plain TS class).
- [ ] `local-storage-preferences.ts` — typed wrapper over `lastStartLocation`, `lastTowAircraftId`, `towPilotByAircraftId[]`, `lastGliderOutbound`, `lastGliderInbound`, `lastTowOutbound`, `lastTowInbound`. Two-mode access: auto-hydrate (`lastStartLocation` only) and explicit "copy from last" buttons.
- [ ] Extend `FlightStore` (shared with S-062b) with detail-state slice: `current: FlightDetail | null`, `currentVersion: number | null`, `save: (dto) => Promise<...>`, `delete: () => Promise<...>`, `loadDetail: (id) => ...`, `loadNewTemplate: () => ...`, `loadCopyTemplate: (id) => ...`. Emits `MutationBus.flightChanged$` on save/delete.
- [ ] `<fls-field-errors>` integration per S-007: each Reactive Form control's i18n error messages render via the primitive.
- [ ] Selector contract: every form input, select, date/time picker, save/cancel/delete button has a stable `data-testid` (`flight-edit-<field>`).
- [ ] Port parity spec `04-flights-create.spec.ts` to new stack — selector adaptation only.
- [ ] Port parity spec `05-flights-edit.spec.ts` to new stack — selector adaptation only.
- [ ] Write new e2e spec `04b-flights-copy.spec.ts`.
- [ ] Write new e2e spec `04c-flights-paired-create.spec.ts`.

## Notes

**Estimate calibration (M):**
- 2 form components + 1 shell + 1 coordinator + 1 defaults helper + 1 form model + 1 prefs service + FlightStore detail-slice extension.
- ~30 cross-field reactive rules (full table below).
- ~750 lines of legacy code referenced (`FlightsController.js:100-820`, `flight-edit-glider-form.html`, `flight-edit-tow-form.html`).
- 4 e2e specs (2 ported, 2 new).
- ~10 unit tests for the coordinator.

**Why this is M not L**: the field rules are dense but mechanical — the coordinator is one class with one `valueChanges` subscription per rule. The risk surface is well-bounded (the parity specs catch behavioral drift end-to-end). Bumps to L only if the architect's "no DI in the coordinator" recipe turns out to fight Angular ergonomics.

**Open risk — selectize replacement.** Legacy specs inject directly on `$scope` to bypass selectize widgets (`04-flights-create.spec.ts:109-138`). The new SPA uses native `<fls-select>` (S-008); spec adaptation goes through `data-testid` instead of `angular.element(form).scope()`. If the replacement primitive isn't a11y/Playwright-friendly, this story balloons.

**Out of scope:**
- Motor-flight form — S-064 owns `/airmovements/*`. The shell here redirects motor flights to that route.
- Validation rejection-path UX depth — S-101.
- Optimistic-concurrency `@Version` column + 412 mapping — S-067 (this story plumbs the `If-Match` header and the toast handler).
- Glider↔Tow cascade / orphan / concurrent edit depth — S-105.

<!-- modernize-refine: start -->

## Design notes

### Module layout — client-side

`next/web/src/app/flights/` (extending what S-062b created):
- `flight-routes.ts` — UPGRADE: replace placeholders with real components.
- `flight.store.ts` — EXTEND: add detail-state slice + `save`/`delete` methods + `MutationBus` emit.
- `services/flight-api.ts` — EXTEND: wrap `getFlight`, `getNewTemplate`, `getCopyTemplate`, `createFlight`, `updateFlight`, `deleteFlight`.
- `pages/flight-edit/flight-edit.component.ts` + `.html` — **shell page**. Owns route params, masterdata loading, save/cancel, glider↔tow time/location coordination. Hosts one of the two form components.
- `pages/flight-edit/glider-flight-form.component.ts` + `.html` — glider form (mirrors `flight-edit-glider-form.html`). Standalone, takes typed `FormGroup<GliderFlightFormModel>` as input.
- `pages/flight-edit/tow-flight-form.component.ts` + `.html` — tow form. Standalone, takes `FormGroup<TowFlightFormModel>` as input. Conditionally rendered when `startType === Towing`.
- `pages/flight-edit/flight-form.model.ts` — typed Reactive Forms model + `buildFlightForm()` factory.
- `pages/flight-edit/flight-form-coordinator.ts` — cross-form orchestration (start-time copy glider→tow, location mirror, duration warning). Plain TS, no Angular DI.
- `pages/flight-edit/flight-form-defaults.ts` — pulls `myClub.DefaultStartType / HomebaseId / DefaultGliderFlightTypeId / DefaultTowFlightTypeId`. Port of `initForNewFlight`.
- `pages/flight-edit/local-storage-preferences.ts` — typed wrapper over the workstation-level UX preferences.
- `masterdata.signals.ts` — EXTEND: per-form-context derived signals (`selectedGliderAircraft`, `selectedFlightType`, `personForInvoiceRequired`, etc.).

### Backend: nothing new
S-062a owns the API. This story only consumes it.

### Integration with other stories

**Inputs:**
- **S-062a**: API endpoints + DTOs.
- **S-062b**: FlightStore list slice; this story extends with detail slice in the same file.
- **S-006**: Signal Store reference + per-domain refetch convention.
- **S-007**: typed Reactive Forms + `<fls-field-errors>`.
- **S-008**: UI primitives kit (`<fls-text-input>`, `<fls-select>`, `<fls-date-picker>`, `<fls-time-picker>`, `<fls-toggle>`).
- **S-051**: Persons + PersonClub (server-filtered dropdown sources).
- **S-050**: Aircraft.
- **S-049**: Locations.
- **S-053**: Flight types.

**Outputs:**
- **S-063** (glider↔tow link integrity): consumes the paired-create form behavior as a parity oracle.
- **S-064** (air movements): mirrors this story's shell pattern with a motor-flight form variant.
- **S-067** (optimistic concurrency): consumes the 412 toast handler shape.
- **S-101** (validator depth): runs validation-rejection scenarios through this form.
- **S-110** (T3 smoke): consumes the edit flow as a navigation step.

### Alternatives considered

**Q1 — Two form components vs. one parametrized form.** Chose **two separate standalone components** sharing a typed-form model. Reason: glider has fields tow doesn't (winch operator, coupon, invoice recipient, passenger, engine counters); one parametrized form would be `*ngIf` soup. Rejected: parametrized component (template becomes 30% conditional rendering); class inheritance (anti-pattern in standalone-signal Angular).

**Q2 — Where does Flight↔Tow orchestration live?** **Server-side, in `FlightApplicationService` under one `@Transactional` boundary** (owned by S-062a). Client `FlightFormCoordinator` only handles UX-level mirroring (start-time, location, outbound route glider→tow at submit time per `FlightsController.js:348-378`).

**Q3 — Coordinator with or without Angular DI?** **Plain TS class, no DI.** Reasons: testable in isolation without Angular Testing Library; explicit `attach(form)` contract; lifecycle owned by the component (`takeUntilDestroyed`). The component injects masterdata signals and passes them in.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — Glider flight form**
- Null/empty: `AircraftId`, `PilotPersonId`, `FlightTypeId`, `StartLocationId`, `LdgLocationId`, `StartTypeId`, `FlightDate` may all be null on the wire — server (S-062a) defers required-field checks to async validation per Q3 in S-062a. Client form **does not** mark these as `Validators.required` unless eager-validation flag flips at the org level.
- Boundary: `NrOfLdgs` accepted as null when `NoLdgTimeInformation=true`; client defaults to `1` on first `ldgTime` blur if unset.
- Boundary: `StartDateTime > LdgDateTime` is not rejected; legacy only warns client-side (`FlightsController.js:590-599`). Preserve.
- Glider with `StartType=SelfStart(3)` / `WinchLaunch(2)` / `ExternalStart(4)` / `MotorFlightStart(5)`: tow sub-form hidden; tow data **kept in memory**, stripped at submit (`prepareForSaving :375-377`).
- Glider with `StartType=Towing(1)`: tow sub-form shown; tow.aircraftId not client-required.
- `IsSoloFlight + CoPilotPersonId` set → CoPilot silently cleared by coordinator (`FlightsController.js:425-427`).
- Engine counters: `EngineEnd < EngineStart` produces 0 duration (`FlightsController.js:767-777`).
- Unauthorized: edit on `ProcessState >= Locked` without ClubAdmin role → `CanUpdateRecord=false` → whole form disabled. Server (S-062a) additionally enforces.
- Cross-tenant: `Pilot/CoPilot/Instructor/Observer/Passenger/WinchOperator` PersonId may belong to a different `OperatingClub` — legitimate. Persons dropdown sourced from `Persons.getGliderPilots()` etc. which already returns the right set.

**AC2 — Tow flight form**
- Tow without parent glider: tow form renders only as sub-section of glider form when `startType=Towing`; no standalone tow route.
- Tow inherits `StartDateTime` + `StartLocationId` + `OutboundRoute` from parent glider at save (`FlightsController.js:370-372`). Tow form has these fields **always disabled**; UI reads from glider via computed signal.
- Boundary: tow landing < glider start → warn but allow.
- Tow `AircraftId` empty → tow data discarded entirely at submit (`prepareForSaving :375-377`).

**AC3 — Single entity, discriminator**
- Discriminator derived server-side from which `*DetailsData` block populated. Client doesn't send a discriminator.
- Concurrent edit: two users edit same flight; second save gets 412 (once S-067 lands). UX: non-blocking toast + refresh action. **Don't retry blindly.**
- Deleted-mid-flow: glider deleted while tow form open → tow form save fails. The shell catches the 404, navigates back to list with an i18n toast.
- Discriminator mid-edit change: legacy doesn't allow Glider → Motor. Form prevents (motor route is separate per S-064).

**AC4 — Copy-flight**
- Copy preserves: `FlightDate`, `StartType`, `GliderFlightDetailsData` (mostly), `TowFlightDetailsData` (mostly).
- Copy clears: `FlightId`, all `StartDateTime`/`LdgDateTime`, `FlightComment`, `CouponNumber`, engine counters (`FlightsController.js:232-254`).
- Copy of `DeliveryBooked` flight: allowed (creates new `NotProcessed` copy).
- Copy of cross-club-owned flight: server (S-062a) returns 404; UI shows i18n toast.
- Unauthorized: any authenticated user can hit `/flights/copy/:id` — no role gate.

**AC5 — Specs 04/05 pass**
- Spec 04 (`04-flights-create.spec.ts:109-131`) injects values directly on `$scope` to bypass selectize widgets — new SPA uses `data-testid` on form fields, dropdowns, save button. Spec adaptation: `await page.getByTestId('flight-edit-aircraft').selectOption(...)`.
- Spec 05 round-trips `FlightComment` via `GliderFlightDetailsData` shape (`05-flights-edit.spec.ts:88, 96-98`). Server (S-062a) preserves the nested-by-discriminator DTO shape per its Q5 → spec port is mechanical.

### Hidden requirements (legacy behavior the story doesn't mention)

- **Tow fields auto-synced from glider on save:** `StartDateTime`, `StartLocationId`, `OutboundRoute` copied glider→tow at `prepareForSaving` (`FlightsController.js:370-372`). Client coordinator handles it; server (S-062a) double-checks as defense in depth.
- **`copyTowingFromLast` + `lastTowAircraftId` in localStorage** (`FlightsController.js:147-152, 348-358`). Workstation-scoped UX. Wrapped in `local-storage-preferences.ts`.
- **`HomebaseId` default** for new flight locations from `myClub` (`FlightsController.js:200-206`).
- **`SoloFlightCheckboxEnablementCalculator`** auto-derives solo-flag from `FlightType.IsSoloFlight`/`IsPassengerFlight` and `Aircraft.NrOfSeats==1` (`FlightsServices.js:75-98`, `FlightsController.js:111-124`). Lives in coordinator.
- **Number-of-seats warning**: `Aircraft.NrOfSeats < FlightType.MinNrOfAircraftSeatsRequired` (`FlightsController.js:583-588`). Non-blocking, client-only.
- **Defaults: `StartType` from `myClub.DefaultStartType || "1"`** and `FlightType` from `myClub.DefaultGliderFlightTypeId` / `DefaultTowFlightTypeId` (`FlightsController.js:198-206, 159`).
- **`GetFlightDetails` returns `CanUpdateRecord`/`CanDeleteRecord` flags** computed server-side (S-062a). Form reads them off the DTO; whole-form-disable when false.
- **Empty-Guid normalization** on load (`tow.AircraftId == '00000000-...'` → `""`): server (S-062a) rejects empty UUIDs at the wire, so client must replace `'00000000-...'` → `null` on load (parity with legacy `mapFlightToForm` `:319-324`).

### Scope clarifications

**In:** create / read / update / copy / delete for glider + tow + glider-with-tow via SPA forms. localStorage workstation prefs. `CanUpdate/CanDelete` flag respected. 412 toast. New e2e specs for copy + paired-create.

**Out:**
- Motor flight form (S-064).
- Async validate-flights workflow trigger (S-083).
- Validation rejection UX depth (S-101).
- State-transition buttons other than save/delete (S-059 / S-102).

### NFR call-outs

- **Performance**: form-load p95 < 3s on Fast 3G (page-load) + fan-out of ~5 master-data GETs. Master-data caching per S-006 makes the second open near-instant.
- **Security**: `canUpdateRecord` disables the whole form; server (S-062a) is the authoritative gate.
- **Accessibility**: selectize is hostile to assistive tech (and to Playwright — `04-flights-create.spec.ts:64`). New form uses `<fls-select>` (S-008) — a11y-tested. WCAG 2.1 AA for the form.
- **i18n**: every label, hint, validation message via i18n key.

## Security plan

### Threat model (form-page-specific)

- **PII echo in localStorage prefs (med)**: `towPilotByAircraftId` keys aircraft → person ID. Person ID alone isn't PII. **OK**.
- **Stored-XSS via free-text fields rendering on edit (med)**: `flight_comment`, `outbound_route`, `inbound_route`, `coupon_number` echoed back into the form on load. Angular bindings use interpolation by default — never `[innerHTML]`. Audit the templates for `bypassSecurityTrustHtml` (must be zero usages).
- **Cross-tenant Person dropdown leak (high)**: Persons dropdowns sourced from `Persons.getGliderPilots()` which is server-filtered per S-062a's input-validation contract (Person must have `PersonClub` for caller's tenant + role). Dropdown options reflect this; the form doesn't fetch person lists from another route.
- **Form bypass of `canUpdateRecord` (high)**: a determined user disables the `[disabled]` binding via DevTools and submits. Server (S-062a) re-checks. **No client gate is sufficient.**
- **412 retry-storm (low)**: 412 toast must not auto-retry. UI shows refresh action; user decides.

### Authorization

Inherits from S-062a. UI hides edit/delete row actions and disables save/delete buttons when `canUpdateRecord` / `canDeleteRecord` is false. Server (S-062a) is the gate.

### Input validation

Inherits from S-062a's server-side validation. Client-side adds:
- `Validators.required` only on fields that are client-required per the field-by-field table (currently: `flightDate`, `aircraftId`, `startLocationId`, `ldgLocationId`, `startType` and conditionally `observerPersonId`, `flightCostBalanceType`, `invoiceRecipientPersonId`, route fields).
- HTML5 `pattern`/`min`/`max` where it matches server expectations (cheap UX preview).

### PII handling

- Form never logs values.
- `MessageManager` toasts use i18n keys + field-path; never the offending value.
- localStorage prefs: per-workstation, per-user via OIDC token? **Investigate** — legacy uses unscoped localStorage. If tokens collide across users on a shared workstation, prefs may leak. Acceptable per legacy parity; document.

## Test plan

### Coverage contract

This story owns **happy-path parity** for create/edit/copy on glider and tow flights through the SPA. Backend coverage is S-062a. List coverage is S-062b. Depth dimensions deferred to S-101 (validation), S-102 (state), S-103 (time gates), S-104 (perms), S-105 (cascade), S-106 (tenant per-endpoint).

### Test pyramid

- **Unit (Vitest)**: ~10 — coordinator rules, form defaults, copy-reset.
- **Component (Angular Testing Library)**: ~5 — visibility rules, disabled-state, field-validity round-trip.
- **E2E (Playwright)**: 4 — 2 parity ports + 2 new.

### Unit tests

`FlightFormCoordinator`:
- `startLocationChange_mirrorsToLdgLocationAndTowFields`.
- `flightTypeChange_isSoloFlight_isForcedTrue_whenFlightTypeIsSolo`.
- `flightTypeChange_isPassengerFlight_isForcedFalse`.
- `aircraftChange_oneSeatAircraft_forcesIsSolo`.
- `aircraftChange_resetsEngineCountersWhenFlagSet`.
- `flightCostBalanceTypeChange_clearsInvoiceRecipient_whenNotRequired`.
- `noStartTimeInformation_propagatesToTow_butNoLdgTimeInformationDoesNot`.
- `isSoloToggleOn_clearsCoPilotPersonId`.

`FlightFormDefaults`:
- `initForNewFlight_appliesMyClubDefaults`.
- `initForNewFlight_useslastStartLocationFromLocalStorageIfPresent`.

`FlightCopyService` (client-side reset on copy template payload):
- (Server owns this in S-062a; client only consumes the cleared payload. No client-side unit test needed.)

### Component tests (Angular Testing Library)

- `gliderForm_disablesTowFieldsWhenStartTypeSelfLaunch` — UI conditional render.
- `gliderForm_enablesTowFieldsWhenStartTypeTowing` — tow form section becomes visible + required-marked.
- `towForm_engineCounterShownOnlyForEngineGliders` — `04-flights-create.spec.ts:96` parity.
- `gliderForm_submitDisabledUntilRequiredFieldsPresent` — mirrors `04:146`.
- `gliderForm_wholeFormDisabledWhenCanUpdateRecordFalse`.

### E2E tests

**Ported (selector adaptation only):**
- `e2e/tests/new/04-flights-create.spec.ts` — port of legacy. `data-testid` adaptation; behavior assertions unchanged.
- `e2e/tests/new/05-flights-edit.spec.ts` — port of legacy. `data-testid` adaptation.

**New:**
- `e2e/tests/new/04b-flights-copy.spec.ts`: create source via API → `/flights/copy/:id` → assert prefill + cleared fields → submit → assert second row distinct from source.
- `e2e/tests/new/04c-flights-paired-create.spec.ts`: `/flights/new` → `StartType=Towing` → fill both sections → submit once → assert two rows in DB (glider with `towFlightId`, tow with matching `flightId`). Legacy `04` uses self-launch to avoid the tow form — this is genuinely new e2e coverage.

### Parity gate

**Cutover gate: zero-delta.** `04` + `05` both green on new stack with byte-identical behavior assertions.

### Risks

- **Selectize-to-fls-select migration drift**: behavior parity (filter, select, blur semantics) — covered by parity specs + component tests.
- **Coordinator complexity**: ~30 cross-field rules. Each is one `valueChanges` subscription + `setValue({emitEvent:false})` — repetitive but mechanical. Risk: a missed `emitEvent:false` causes an infinite loop. Mitigation: unit-test each rule's effect independently.
- **Calculated `FlightAirState` reads "now"** — must be pure on timestamps to avoid test flakiness. S-060 verifies.

## Performance plan

### Hot paths

- **Page-load on `/flights/new` or `/flights/:id`**: cold-cache LCP < 3s on Fast 3G. Form bundle + master-data fan-out + initial GET share this budget.
- **`GET /flights/{id}`**: p95 < **150ms** server-side (owned by S-062a).
- **`PUT /flights/{id}`**: p95 < **300ms** server-side; UI shows a save-in-progress indicator if > 500ms.
- **`POST /flights`**: p95 < **300ms** server-side.

### Form-load fan-out

Each form open triggers ~5 GETs (aircraft, persons, locations, flight types, start types). Mitigations:
- Master-data signal stores are **cache-long** per S-006. Second open near-instant.
- First open of the session: fan-out the 5 GETs in parallel; don't serialize them behind the flight GET.
- Show the form shell as soon as the flight detail returns; resolve dropdown options as masterdata trickles in.

### Caching strategy (client-side)

- **Single-flight (edit) store slice**: load on route entry; clear on route exit. No cross-route caching.
- **Master-data**: cache-long.
- **localStorage workstation prefs**: per-workstation, opt-in by user via "copy from last" buttons (except `lastStartLocation` which auto-hydrates).

### Bundle size

- Form components, coordinator, defaults helper, prefs service — code-split via the route loader.
- Reactive Forms + field-error primitives are shared chunks.

### Memory considerations

- Form-detail payload ~10KB. Negligible.
- No streaming concerns.

## Client form mechanics

(Carried verbatim from the original S-062 refinement, which already covered this in depth.)

### Form structure

```ts
// Legacy DTO shape (preserved in modern form):
//   FlightDetails { FlightId, FlightDate, StartType, CanUpdateRecord, CanDeleteRecord,
//                   GliderFlightDetailsData, TowFlightDetailsData (nullable when !needsTowplane) }
// Crew fields are flat scalars on each *DetailsData block, NOT a nested crew array
// (FlightsController.js:425-427, 516, 529, 543, 557; flight-edit-glider-form.html:73,141,163,190,209,228).
// So the typed form mirrors that shape — *not* a nested per-crew-member group.

type FlightForm = FormGroup<{
  flightId:  FormControl<string | null>;          // hidden; null on create
  flightDate: FormControl<Date | null>;           // top-level (flight-edit-form.html:17-20)
  startType:  FormControl<number | null>;         // top-level (FlightsController.js:198, 666-673)

  canUpdateRecord: FormControl<boolean>;          // server-supplied permission (FlightService.cs:1741-1770)
  canDeleteRecord: FormControl<boolean>;

  glider: FormGroup<GliderFlightForm>;            // always present
  tow:    FormGroup<TowFlightForm>;               // present iff startType === Towing (FlightsController.js:418-420, 666-673)
}>;

type GliderFlightForm = {
  aircraftId:        FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  coPilotPersonId:   FormControl<string | null>;
  instructorPersonId:FormControl<string | null>;
  observerPersonId:  FormControl<string | null>;
  passengerPersonId: FormControl<string | null>;
  winchOperatorPersonId: FormControl<string | null>;

  startLocationId:   FormControl<string | null>;
  ldgLocationId:     FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;
  inboundRoute:      FormControl<string | null>;

  startTime:         FormControl<string | null>;  // HH:mm
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;  // derived; also editable (FlightsController.js:738-743)
  noStartTimeInformation: FormControl<boolean>;
  noLdgTimeInformation:   FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  engineStartOperatingCounterInSeconds: FormControl<number | null>;
  engineEndOperatingCounterInSeconds:   FormControl<number | null>;
  engineDurationSeconds: FormControl<number | null>; // computed mirror

  flightCostBalanceType:     FormControl<number | null>;
  invoiceRecipientPersonId:  FormControl<string | null>;
  couponNumber:              FormControl<string | null>;
  flightComment:             FormControl<string | null>;

  isSoloFlight:      FormControl<boolean>;        // auto-derived (FlightsServices.js:75-98)
};

type TowFlightForm = {
  aircraftId:        FormControl<string | null>;
  pilotPersonId:     FormControl<string | null>;
  flightTypeId:      FormControl<string | null>;

  // startLocationId / startDateTime / outboundRoute are MIRRORS of glider's values
  // (FlightsController.js:370-372). Modeled as disabled controls bound to a computed signal.
  startLocationId:   FormControl<string | null>;
  startTime:         FormControl<string | null>;
  outboundRoute:     FormControl<string | null>;

  ldgLocationId:     FormControl<string | null>;
  ldgTime:           FormControl<string | null>;
  duration:          FormControl<string | null>;
  noLdgTimeInformation: FormControl<boolean>;

  nrOfLdgs:          FormControl<number | null>;
  inboundRoute:      FormControl<string | null>;
  flightComment:     FormControl<string | null>;
};
```

**Notes:**
- No per-crew-member nested FormGroup in legacy. If new server API normalizes to a `crew[]` collection (per S-058), do that mapping in the API client, **not** in the form.
- `version` for optimistic concurrency lives in the FlightStore alongside the form, not on the form itself (S-067).

### Field-by-field rules

| Field | Required when | Visible when | Disabled when | Default | Notes / legacy cite |
|---|---|---|---|---|---|
| `flightDate` | always (HTML `required`) | always | `!canUpdateRecord` | new: `today` if no StartDateTime; copy: `res.FlightDate` | `flight-edit-form.html:17-20`; `FlightService.cs:1075-1076` |
| `startType` | always (server `:1096-1097`) | always | `!canUpdateRecord` | `flightDetails.StartType` ‖ `myClub.DefaultStartType` ‖ `"1"` | `FlightsController.js:198, 666-673` |
| `glider.flightTypeId` | server (`:1099-1100`); no client `ng-required` | always | `!canUpdateRecord` | `myClub.DefaultGliderFlightTypeId` | `flight-edit-glider-form.html:87-98`; `FlightsController.js:202` |
| `glider.aircraftId` | client (HTML `required`); server (`:1078-1079`) | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:17-30` |
| `glider.pilotPersonId` | server (`:1081-1082`); no client `required` | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:63-73` |
| `glider.coPilotPersonId` | never | `!isSoloFlight && !flightType.IsPassengerFlight && !flightType.InstructorRequired` | `!canUpdateRecord` | none; **auto-cleared** when `isSoloFlight==true` | `flight-edit-glider-form.html:173-191`; `FlightsController.js:425-427` |
| `glider.instructorPersonId` | `flightType.InstructorRequired` (visibility implies it) | `flightType.InstructorRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:192-210` |
| `glider.observerPersonId` | `ng-required="flightType.ObserverPilotOrInstructorRequired"` | same | `!canUpdateRecord` | none | `flight-edit-glider-form.html:123-143` |
| `glider.passengerPersonId` | client: not enforced; intent: required when `IsPassengerFlight` | `flightType.IsPassengerFlight` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:145-172` |
| `glider.winchOperatorPersonId` | server: `startType==WinchLaunch` (`:1024-1030`); client: visibility only | `startType.IsWinchStart` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:211-229` |
| `glider.startLocationId` | server (`:1090-1091`) | always | `!canUpdateRecord` | `localStorage.lastStartLocation` ‖ `myClub.HomebaseId` | `FlightsController.js:200` |
| `glider.ldgLocationId` | server (`:1093-1094`) | always | `!canUpdateRecord` | same chain; **mirrored** when `startLocationId` changes | `FlightsController.js:201, 650` |
| `glider.outboundRoute` | `startLocation.IsOutboundRouteRequired`; server `:1112-1123` checks against allow-list | `isOutboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderOutbound` | `FlightsController.js:217-219, 703-704` |
| `glider.inboundRoute` | `landingLocation.IsInboundRouteRequired`; server `:1125-1135` | `isInboundRouteRequired` | `!canUpdateRecord` | none; "copy from last" reads `lastGliderInbound` | `FlightsController.js:704` |
| `glider.startTime` | server when `!noStartTimeInformation` (`:1084-1085`) | always | `!canUpdateRecord ‖ noStartTimeInformation` | none; "now" button → current time | `FlightsController.js:716-720, 808-812` |
| `glider.ldgTime` | server when `!noLdgTimeInformation` (`:1087-1088`) | always | `!canUpdateRecord ‖ noLdgTimeInformation` | none | `FlightsController.js:731-736, 814-817` |
| `glider.duration` | never (derived) | always | `!canUpdateRecord` | computed; editing back-computes ldg | `FlightsController.js:601-617, 738-743` |
| `glider.noStartTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | toggle clears `startTime`; **also sets `tow.NoStartTimeInformation`** `FlightsController.js:808-812` |
| `glider.noLdgTimeInformation` | n/a | always | `!canUpdateRecord` | `false` | `FlightsController.js:814-817` |
| `glider.nrOfLdgs` | server: required iff `ldgTime` set (`:1102-1109`); `@Min(1)` | always | `!canUpdateRecord` | `1` on new; `1` on first `ldgTime` blur if unset | `FlightsController.js:203, 727` |
| `glider.engineStartOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change when `resetEngineOperatingCounters=true` | `FlightsController.js:115-116, 128-136` |
| `glider.engineEndOperatingCounterInSeconds` | never client | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | reset on aircraft change | `flight-edit-glider-form.html:418-429` |
| `glider.engineDuration` (computed) | never | `selectedGliderAircraft.HasEngine` | `!canUpdateRecord` | computed `end - start`, floored at 0 | `FlightsController.js:767-785` |
| `glider.flightCostBalanceType` | `ng-required="flightType.IsFlightCostBalanceSelectable"` | same | `!canUpdateRecord` | `1` on new | `flight-edit-glider-form.html:450-468`; `FlightsController.js:192` |
| `glider.invoiceRecipientPersonId` | `ng-required="PersonForInvoiceRequired"` | `PersonForInvoiceRequired && flightType.IsFlightCostBalanceSelectable` | `!canUpdateRecord` | none; **cleared** when `PersonForInvoiceRequired` becomes false | `FlightsController.js:562-573` |
| `glider.couponNumber` | never | `flightType.IsCouponNumberRequired` | `!canUpdateRecord` | none | `flight-edit-glider-form.html:494-502` |
| `glider.flightComment` | never | always | `!canUpdateRecord` | none | `flight-edit-glider-form.html:442-449` |
| `glider.isSoloFlight` | n/a (derived) | always (icon) | `!flightTypeCheckbox.isChangingAllowed ‖ !canUpdateRecord` | derived: `flightType.IsSoloFlight→true`; `IsPassengerFlight→false`; else preserve | `FlightsServices.js:75-98`; `FlightsController.js:111-124, 575-581` |
| `tow.aircraftId` | server-required when tow validated; client: no `required` | `startType==Towing` | `!canUpdateRecord` | `lastTowAircraftId` (copy-button only) | `FlightsController.js:147-152` |
| `tow.pilotPersonId` | server (`:1081-1082` on tow row) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `towPilotByAircraftId[aircraftId]` localStorage (copy-button) | `FlightsController.js:147-152, 350-352` |
| `tow.flightTypeId` | server (`:1099-1100` on tow) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.DefaultTowFlightTypeId` (set on aircraft selection) | `FlightsController.js:159` |
| `tow.startTime` | (server when `!noStartTimeInformation`) | `startType==Towing` | **always disabled** (mirrors `times.gliderStart`) | mirror of glider | `flight-edit-tow-form.html:95-101`; `FlightsController.js:370` |
| `tow.startLocationId` | server (`:1090-1091`) | `startType==Towing` | **always disabled** (`ng-disabled="true"`); mirrors glider | mirror of glider; default `myClub.HomebaseId` | `flight-edit-tow-form.html:145-157`; `FlightsController.js:205-206, 371, 650-654` |
| `tow.outboundRoute` | server (when required) | `isOutboundRouteRequired` | **always disabled**; mirrors glider | mirror | `flight-edit-tow-form.html:188-197`; `FlightsController.js:372` |
| `tow.ldgLocationId` | server (`:1093-1094`) | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `myClub.HomebaseId` (via `resetTowFlightDefaults`) | `FlightsController.js:158` |
| `tow.ldgTime` | server when `!noLdgTimeInformation` | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId ‖ noLdgTimeInformation` | none | `FlightsController.js:745-758, 819-822` |
| `tow.noLdgTimeInformation` | n/a | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `false` | **not** mirrored from glider | `FlightsController.js:819-822` |
| `tow.duration` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | derived | `FlightsController.js:760-765` |
| `tow.nrOfLdgs` | server: required iff tow `ldgTime` set | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | `1` via `resetTowFlightDefaults`; `1` on first `formatTowLanding()` if unset | `FlightsController.js:160, 749` |
| `tow.inboundRoute` | server (when required) | `isInboundRouteForTowFlightRequired` | `!canUpdateRecord ‖ !tow.aircraftId` | none; "copy from last" reads `lastTowInbound` | `FlightsController.js:705` |
| `tow.flightComment` | never | `startType==Towing` | `!canUpdateRecord ‖ !tow.aircraftId` | none | `flight-edit-tow-form.html:225-233` |

**No `accountingRemark` field exists** in legacy glider/tow forms (grep confirmed). Out of scope; flag as a new requirement if business asks.

### Cross-field reactive rules

- **`startType` changes** → if Towing and `TowFlightDetailsData == null`, create empty `tow` block. Recompute `selectedStartType` for `IsWinchStart` visibility. `FlightsController.js:418-420, 666-673`.
- **`startType` changes to non-Towing** → tow block hidden via `@if`; tow data **kept in memory**, stripped at submit (`prepareForSaving :375-377`).
- **`glider.flightTypeId` changes** → recompute `selectedFlightType` → re-derive solo via `SoloFlightCheckboxEnablementCalculator.getSoloFlightCheckbox(...)` (`FlightsController.js:430-441`; `FlightsServices.js:75-98`):
  - `flightType.IsSoloFlight==true` → force `isSoloFlight=true`, checkbox `CHECKED`, not changeable.
  - `flightType.IsPassengerFlight==true` → force `isSoloFlight=false`, checkbox `UNCHECKED`, not changeable.
  - else → preserve existing, checkbox toggleable.
  - Also recompute `warnNumberOfSeatsInsufficientForFlightType` (`:583-588`).
- **`isSoloFlight` toggles to `true`** → `coPilotPersonId = undefined` (`FlightsController.js:425-427`).
- **`glider.aircraftId` changes** → set `selectedGliderAircraft`, `gliderCompetitionSign`. If `NrOfSeats===1 && !IsSoloFlight` → force `IsSoloFlight=true`. If `HasEngine` → fetch `AircraftOperatingCounters`, refresh `lastOperatingCounterFormatted`. Optionally clear engine counters. Recompute seat warning. (`FlightsController.js:110-145`.)
- **`glider.flightCostBalanceType` changes** → set `$scope.PersonForInvoiceRequired`. If false, **clear `glider.invoiceRecipientPersonId`** (`FlightsController.js:562-573`).
- **`tow.aircraftId` changes** → set `towplaneRegistration`. Run `resetTowFlightDefaults`: fill `tow.startLocationId / ldgLocationId / flightTypeId` from `myClub` defaults if empty; `tow.nrOfLdgs = 1`. (`FlightsController.js:163-188, 155-161`.)
- **`glider.startLocationId` changes** → `glider.ldgLocationId = glider.startLocationId` (overwrite); also mirror to `tow.startLocationId` and `tow.ldgLocationId`; recompute route requirements. (`FlightsController.js:649-656`.)
- **`glider.ldgLocationId` changes** → recompute `isInboundRouteRequired` (`FlightsController.js:658-660, 704`).
- **`tow.ldgLocationId` changes** → recompute `isInboundRouteForTowFlightRequired` (`FlightsController.js:662-664, 705`).
- **Location lookups resolve** → load `outboundRoutes` / `inboundRoutes` lists via `RoutesPerLocation` for selectize options (`FlightsController.js:690-701`).
- **`glider.noStartTimeInformation` toggles** → clear `times.gliderStart`; **propagates to `tow.NoStartTimeInformation`** (`FlightsController.js:808-812`). Asymmetric: glider landing toggle does *not* propagate (`:814-817`).
- **`glider.startTime` blur** → recompute `times.gliderDuration` and `times.towingDuration` (both anchored to glider start) (`FlightsController.js:709-714`).
- **`glider.ldgTime` blur** → recompute `gliderDuration`; **default `nrOfLdgs=1` if unset** (`FlightsController.js:723-729`).
- **`glider.duration` blur** → back-compute `times.gliderLanding = start + duration` (`FlightsController.js:738-743`).
- **`tow.ldgTime` blur** → recompute `towingDuration`; default `tow.nrOfLdgs=1` if unset (`FlightsController.js:745-751`).
- **`tow.duration` blur** → back-compute `times.towingLanding` (`FlightsController.js:760-765`).
- **Engine counters blur** → recompute `engineSecondsCounterDuration = max(0, end - start)` (`FlightsController.js:767-777`). **`engineDuration` blur** → recompute `engineEnd = engineStart + duration` (`FlightsController.js:779-785`).
- **Glider start + tow landing both valid** → `warnTowFlightLongerThanGliderFlight = gliderDuration < towDuration` (`FlightsController.js:590-599`). Warning only.

### Visibility-mode matrix

`flightAircraftType`: 1 = GliderFlight, 2 = TowFlight (derived, never user-edited), 4 = MotorFlight (separate route under `airmovements/`, out of S-062c).

| `startType` | `aircraftType` | Glider block | Tow block | Engine counters (glider) | Winch operator | Notes |
|---|---|---|---|---|---|---|
| Towing (1) | GliderFlight | shown | shown | iff `glider.HasEngine` | hidden | Default; `needsTowplane=true` (`FlightsController.js:418-420`). Tow row created+linked. |
| WinchLaunch (2) | GliderFlight | shown | hidden | iff `glider.HasEngine` | shown + server-required (`:1024-1030`) | `flight-edit-tow-form.html:2` `ng-if="needsTowplane"` hides tow column. |
| SelfStart (3) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Self-launching motor glider — engine block usually applies. |
| ExternalStart (4) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Server validates **no** tow linked (`:1017-1022`). |
| MotorFlightStart (5) | GliderFlight | shown | hidden | iff `glider.HasEngine` | hidden | Unusual; server accepts (`:1036-1039`). |
| any | MotorFlight | **N/A — separate route** `/airmovements/...` owned by **S-064**. Form structurally different. | | | | flag boundary. |

Engine-counter visibility is `glider.HasEngine`, independent of `startType` (`flight-edit-glider-form.html:394, 419, 431`).

### Disabled-state rules

- **Whole form**: disabled when `!flightDetails.CanUpdateRecord` (server-supplied). Derivation: `processState >= Locked && (!IsClubAdministrator || processState == DeliveryBooked)` → false; else true. Source: `FlightService.cs:1741-1770` (`SetFlightDetailsSecurity`); mirrored on overviews at `:1675-1687`.
- **Legacy server gap closed in S-062a**: `UpdateFlightDetails` only hard-blocks `DeliveryBooked` in legacy. New server rejects `Locked`/`DeliveryPrepared` for non-admins.
- **Delete button**: gated by `CanDeleteRecord`; same derivation. `DeliveryBooked` rejects at server (`:1308-1312`).
- **Tow sub-controls extra gate**: `tow.pilotPersonId`, `tow.flightTypeId`, `tow.ldgTime`, `tow.ldgLocationId`, `tow.nrOfLdgs`, `tow.inboundRoute`, `tow.flightComment`, `tow.duration` are additionally disabled when `!tow.aircraftId`. Cite: every `ng-disabled` in `flight-edit-tow-form.html:64, 90, 109, 135, 175, 185, 206, 220, 231`.
- **`tow.startLocationId` / `tow.startTime` / `tow.outboundRoute`**: **always** disabled (`ng-disabled="true"` / `disabled`) — mirrored from glider at submit (`flight-edit-tow-form.html:155, 100, 195`).
- **Time fields gated by their "no info" flag**: `glider.startTime` disabled when `noStartTimeInformation`; same for landing. (`flight-edit-glider-form.html:237, 263`; `flight-edit-tow-form.html:109`.)
- **Role-driven** (overriding above): `IsClubAdministrator` users can edit `Locked` / `DeliveryPrepared` / `DeliveryPreparationError` / `ExcludedFromDeliveryProcess` flights — everything except `DeliveryBooked`. Already encoded in `CanUpdateRecord`; SPA needs no separate role check.

### Default-value derivation

**New flight** (`/flights/new` → fetches `GET /flights/new-template` from S-062a → `FlightFormDefaults` applies any client-only overlays):

- Server returns a fully-populated draft with `myClub` defaults applied (port of `initForNewFlight`).
- Client-only overlays: `localStorage.lastStartLocation` if present takes precedence over server `myClub.HomebaseId` for start/ldg locations (parity).

**Copy** (`/flights/copy/:id`): server fetches `GET /flights/{id}/copy-template` (S-062a) which returns the cleared draft. Client applies the same `lastStartLocation` overlay.

**localStorage hydration** (workstation-scoped UX convenience):

- `lastTowAircraftId` (written at save `:353`) + `towPilotByAircraftId[aircraftId]` (`:350-352`) — hydrated **only on explicit "copy from last" button click** in tow aircraft field (`flight-edit-tow-form.html:36-38` → `copyTowingFromLast` at `:147-152`). Not auto-applied on form load.
- `lastStartLocation` (written `:359`) — **auto-hydrated** as default for both glider start/ldg and tow start/ldg on new flight (`:200-201, 205-206`).
- `lastGliderOutbound`, `lastGliderInbound`, `lastTowOutbound`, `lastTowInbound` — written on save (`:354-358`); hydrated only via per-field "copy from last" history button (`copyRouteFromLast` `:217-219`).

### Submit-time transformations

`prepareForSaving(flightDetails)` (`FlightsController.js:348-378`) — implemented by `FlightFormCoordinator.toDto(form)`:

1. **Persist localStorage** for next session: write `towPilotByAircraftId[towAircraftId]`, `lastTowAircraftId`, `lastTowOutbound/Inbound`, `lastGliderOutbound/Inbound`, `lastStartLocation` (`:348-359`).
2. **Compose datetimes** from `flightDate` + `times.gliderStart/gliderLanding/towingLanding` into ISO datetimes (`:364-366, 373`).
3. **Glider→tow sync** (always, before discard check): `tow.StartDateTime = glider.StartDateTime`; `tow.StartLocationId = glider.StartLocationId`; `tow.OutboundRoute = glider.OutboundRoute` (`:370-372`).
4. **Tow discard**: if `!needsTowplane(startType) || !tow.AircraftId` → `flightDetails.TowFlightDetailsData = undefined` (`:375-377`). Partial tow data the user filled in is dropped.

`mapFlightToForm(result)` on **load** — reverse-direction normalization (`:317-346`):

5. **Empty-Guid normalization**: `tow.AircraftId == '00000000-0000-0000-0000-000000000000'` → `null`; same for `tow.PilotPersonId` (`:319-324`). Server (S-062a) rejects empty UUIDs at the wire — client must normalize on load.

`flightTypeChanged()` / `flightCostBalanceTypeChanged()` / `recalcCheckboxState()` — applied at edit time, also takes effect before save:

6. **CoPilot clear when solo** — `flightTypeCheckbox.state === 'CHECKED'` → `glider.CoPilotPersonId = undefined` (`:425-427`).
7. **InvoiceRecipient clear when not required** — `PersonForInvoiceRequired` flips false → `glider.InvoiceRecipientPersonId = undefined` (`:562-573`).
8. **`IsSoloFlight` force-set** by aircraft seat count (`NrOfSeats===1 && !IsSoloFlight` → `true`) on aircraft change (`:121-124`).

Mass-assignment defense: form **does not** include `processState`, `operating_club_id`, `owner_id`, `validation_errors`, `version`, audit columns in the DTO at submit — server (S-062a) rejects them anyway.

### Recommended Angular implementation

**Required-when patterns.** Single `effect()` re-derives validators from a `formValue` signal. Avoid `Validators.required` in the initial builder for conditionally-required fields — set dynamically. Use `setValidators` + `updateValueAndValidity({emitEvent:false})` to avoid re-trigger loops.

```ts
effect(() => {
  const ft = flightTypeSig();          // computed from form value + flightTypes signal
  const required = !!ft?.observerPilotOrInstructorRequired;
  const c = form.controls.glider.controls.observerPersonId;
  c.setValidators(required ? [Validators.required] : []);
  c.updateValueAndValidity({ emitEvent: false });
});
```

**Visible-when patterns.** Drive template via `computed()` signals over `formValue = toSignal(form.valueChanges, { initialValue: form.getRawValue() })`. Template uses `@if`. **Disable hidden controls** so they don't contribute to validity.

```ts
readonly showWinchOperator = computed(() => this.selectedStartType()?.isWinchStart === true);
readonly showInstructor    = computed(() => this.selectedFlightType()?.instructorRequired === true);
readonly showCoPilot       = computed(() =>
  !this.formValue().glider.isSoloFlight
  && !this.selectedFlightType()?.isPassengerFlight
  && !this.selectedFlightType()?.instructorRequired);
```

Pair each visibility computed with an `effect` that enables/disables the control. Reactive form `[disabled]` binding is a footgun; use `control.disable({emitEvent:false})`.

**Cross-field value derivation.** Coordinator pattern as plain TS — `FlightFormCoordinator` lives in `pages/flight-edit/flight-form-coordinator.ts`, no Angular DI. Subscribes to `valueChanges` of specific controls and writes through.

```ts
// FlightFormCoordinator.attach(form) — called once from FlightEditComponent.ngOnInit()
form.controls.glider.controls.startLocationId.valueChanges
   .pipe(takeUntilDestroyed(destroyRef))
   .subscribe(id => {
     form.controls.glider.controls.ldgLocationId.setValue(id, { emitEvent: false });
     form.controls.tow?.controls.startLocationId.setValue(id, { emitEvent: false });
     form.controls.tow?.controls.ldgLocationId.setValue(id, { emitEvent: false });
   });
```

Same pattern for: `flightTypeId` → solo derivation + `coPilotPersonId` clear; `aircraftId` → force-solo-if-1-seat + reset engine counters; `flightCostBalanceType` → toggle `personForInvoiceRequired` + clear invoice recipient when needed; `noStartTimeInformation` → propagate to tow.

**Disabled-state binding.** Server's `canUpdateRecord` flag flows through FlightStore. On detail load, after `form.patchValue(dto)`, call `form.disable({emitEvent:false})` when `!canUpdateRecord`. For tow's per-field `!tow.aircraftId` gate, an `effect` toggles per-control enable/disable:

```ts
effect(() => {
  const towEnabled = !!this.formValue().tow?.aircraftId && this.canUpdate();
  for (const key of ['pilotPersonId','flightTypeId','ldgTime','ldgLocationId',
                     'nrOfLdgs','inboundRoute','flightComment','duration']) {
    const c = (form.controls.tow.controls as any)[key];
    towEnabled ? c.enable({emitEvent:false}) : c.disable({emitEvent:false});
  }
});
```

`tow.startLocationId` / `tow.startTime` / `tow.outboundRoute` get permanently `.disable()`'d in the builder.

**Where `FlightFormCoordinator` plugs in.** Instantiated once by `FlightEditComponent.ngOnInit()`, given the `FormGroup` + masterdata signals (`gliderAircrafts`, `gliderFlightTypes`, `flightCostBalanceTypes`, `myClub`). Owns: (a) all `valueChanges` subscriptions for cross-field derivations; (b) the submit-time transformation pipeline (`prepareForSaving` equivalent); (c) "copy from last" actions (delegates to `LocalStoragePreferences`). The coordinator does **not** own visibility computeds — those live on the component because templates bind to them directly. The coordinator only writes form values; visibility is a pure projection.

## Open design questions

These are the form-relevant open questions carried from the original S-062. Backend-specific questions live in S-062a.

1. **Route allow-list pre-validation client-side.** Server (S-062a) validates `outboundRoute`/`inboundRoute` values against the location's `InOutboundPoints` allow-list. Client currently shows autocomplete from the list but accepts free text. Decide: pre-validate client-side (better UX; saves a round-trip on rejection) or keep the legacy free-text behavior. Related to S-062a's Q3 (eager-vs-deferred validation).

2. **Server-required-but-no-client-`required` fields.** Four fields are required by the server but have no client-side `ng-required` attribute in legacy: `glider.flightTypeId`, `glider.pilotPersonId`, `tow.flightTypeId`, `tow.pilotPersonId`. Legacy lets the form save without them — the flight just lands in `NotProcessed`. If S-062a chose eager rejection at create (currently it didn't — see Q3 there), these need `Validators.required` client-side too — otherwise the SPA's "save" button enables but the POST returns 400. **Currently aligned with deferred validation.**

3. **`tow.noStartTimeInformation` modeling.** The flag is set indirectly via glider toggle propagation (`FlightsController.js:810`) but has no dedicated UI control on the tow form. **Currently modeled** as a hidden derived control on the new tow form (mirrors glider). Server-side computation is the alternative (cleaner contract). Confirm.

4. **`FlightStateMapper` enum drift (R5).** Both `FlightProcessState` (stored) and `FlightAirState` (computed) flow to the SPA. The new system derives both from the generated OpenAPI client (closing R5). Confirm `FlightAirState` is included in the OpenAPI spec as an enum, not stringified ad-hoc. Verify via S-004 codegen output.

<!-- modernize-refine: end -->

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

The vision-doc amendment 2026-05-15b (see [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §C21–C24 + §F1–F16) designates the flight-edit form as **the** airfield hot-path screen alongside the flight list (S-062b). This story is the primary landing zone for that directive.

**Layered acceptance criteria (additive to existing AC list — do not remove the parity ACs):**

- **AC-DIR-1 (mobile-first single-column layout).** At viewports `<lg` (< 1024 px), the form renders as a single column with sectioned accordion (Aircraft → Pilots → Times → Locations → Costs); glider and tow are accordion siblings, not side-by-side. Sticky save bar anchored to viewport bottom. (Vision §F2.)
- **AC-DIR-2 (dense-desktop variant).** At `≥lg` (≥ 1024 px), the form renders in a dense multi-column grid (4 columns at `xl`, glider + tow side-by-side as legacy intends but tighter padding + inline labels). **Same component**, breakpoint-driven layout — no parallel desktop component (C22). (§F3.)
- **AC-DIR-3 (keyboard-only completion on dense).** On `≥lg`: Tab / Shift+Tab natural order; Enter = save; Esc = cancel-with-dirty-confirm; Ctrl+D = save+copy; number keys 1–5 select the most-common flight-types. Playwright spec asserts the form saves with zero mouse events. (§F4, §2 NFR "keyboard-only completion".)
- **AC-DIR-4 ("Copy from Last" preserved as first-class).** Existing localStorage-backed `local-storage-preferences.ts` + per-field "Copy from Last" buttons remain unchanged. They are not replaced by AC-DIR-5. (C24, §F5.)
- **AC-DIR-5 (smart defaults from server context).** When the form opens blank (no localStorage hint, no copy), the SPA calls `GET /api/v1/flights/last-context?aircraftId=<club-default>&date=<today>` (added in S-062a) and patches with the last-saved field combo. Smart defaults **never** overwrite an explicit "Copy from Last" action. Empty response → falls back to `flight-form-defaults.ts`. (§F6, §F7.)
- **AC-DIR-6 (recency-biased autocompletes).** All dropdowns (aircraft, pilot, observer, passenger, location, route) surface "recently used by this user, last 7 days" at the top of the list before the rest of the catalog. Consumes `<fls-autocomplete>` primitive from S-008. (§F8.)
- **AC-DIR-7 (inline validation, not on-blur).** Errors pin next to the offending field; update as the user types / moves focus. Soft pref §4. Supersedes legacy on-blur + top-message-bar pattern. (§F9.)
- **AC-DIR-8 (native input types).** `<input type="time">` (native mobile picker); `<input type="date">`; `inputmode="numeric"` for counters / nrOfLdgs. The `<fls-time-now-button>` primitive (S-008) wraps the legacy "Set Now" semantics on top of native time inputs. No text-with-format-on-blur. (§F10, §F14.)
- **AC-DIR-9 (auto-save draft to IndexedDB).** Form debounce-saves (500 ms) the in-progress draft to IndexedDB on every field change. On connection loss, queued via PWA service worker (C18 / ADR 0014). On reload, draft restored with "continue from draft / start fresh" prompt. (§F12.)
- **AC-DIR-10 (touch-target compliance).** Primary actions on mobile viewports ≥ 44 × 44 CSS px hit area; on dense desktop, ≥ 28 × 28 px for icon-only secondary actions. Enforced by primitives kit (S-008); verified by axe-core in Playwright. (§2 NFR "touch targets".)
- **AC-DIR-11 (time-to-log benchmark).** Scripted Playwright "stopwatch" test logs a typical glider-with-tow flight on dense desktop in ≤ 60 s and on phone viewport (360 × 640) in ≤ 90 s. Recorded per release; informational, not a blocking gate. (§2 NFR "time-to-log".)
- **AC-DIR-12 (online 409 conflict UX).** When a `PUT` returns 409 (via the `@Version` check from S-067), the form shows the diff inline with per-field "keep mine / keep theirs", keeps the draft visible, and never auto-retries. Applies in addition to the existing AC-9 412 toast. (§F13, soft pref §4 "optimistic-concurrency UX".)
- **AC-DIR-13 (smooth conditional sections).** Dependent fields (e.g. tow block when StartType=Towing; instructor when `InstructorRequired`) appear/disappear via Signal-Store render control; 150 ms slide-in; focus moves to first new field. No layout jank. (§F15.)
- **AC-DIR-14 (marginal-connectivity graceful degradation).** At simulated 200 ms RTT + intermittent loss: dropdown data served from Signal Store cache; save attempts queue via service worker; no spinner > 3 s blocks the user. (§2 NFR "marginal-connectivity graceful degradation".)

**Refinement status flag:** This story was refined on 2026-05-14, *before* the 2026-05-15b directive. The existing form-store + coordinator + prefs-service design accommodates the directive without architectural change, but the design-notes, test-plan, and performance-plan sections were written without it. **Recommend `/modernize-refine S-062c` is re-run before implementation begins** so the directive folds into the per-section refinement rather than living as an appended block.

**Inputs picked up from sibling stories:**

- S-008 — `<fls-autocomplete>` with recency-bias, `<fls-time-now-button>`, density tokens, breakpoint utilities, touch-target lint.
- S-007 — inline-validation + native-input form convention.
- S-062a — `GET /api/v1/flights/last-context` endpoint.
- S-006 — Signal-Store-driven conditional render + aggressive prefetch on app start.
- S-067 + ADR 0014 — conflict + offline machinery.
- S-067 — `@Version` column + 409 / 412 surfacing.

<!-- amendment-2026-05-15b: end -->
