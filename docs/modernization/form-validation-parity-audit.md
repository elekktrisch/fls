# Form-validation parity audit (2026-06-09)

**Method:** ultracode workflow — 12 forms, each pipelined through `legacy-oracle` (extract the
legacy validation bar) → parity review (read the AF validators + as-you-type wiring + any
`/validate` call) → `gap-hunter` adversarial verify (confirm/refute each claimed gap against
source) → synthesis. 37 agents. Only **confirmed** gaps below; notable refutations listed so they
are not re-actioned.

**Operator bar:** (1) legacy = **minimum** (AF may exceed); (2) **all** validations run
**as-you-type** (debounced ~200ms), not only on submit/blur/touched; (3) **server-on-submit stays
the safety-bearing step** (the as-you-type server pre-check is UX, not the guarantee).

## 1. Executive summary

**Good on safety, weak on as-you-type.** After verification, **no form is hollow-green** — submit-time
server safety holds on all 12, and AF usually *exceeds* legacy (real `@TenantId` tenancy, clean
400/404/409 where legacy 500'd, write-authz legacy lacked). The systemic deficiency is the **J-6b
as-you-type bar**: wired only on the two reference forms (reservation-edit, planning-edit), **missing
on the other 10**.

**Fully compliant (meet every operator bar):** Reservation edit, Planning-edit (the J-6b references).

**Three HIGH-severity holes (real, below-bar / data-loss):**
1. **Flight-type — FlightCode duplicate → raw 500** (reproduces the legacy bug; the service pre-checks
   only the name, no DIVE handler for `ux_flight_type_club_code`).
2. **Flight-type — Instructor × Observer mutual-exclusion enforced at NO layer** (legacy `CHECK` forbids
   `(1,1)`; AF accepts it client, domain, and DB).
3. **Person/Member edit — UPDATE silently drops all membership edits.** The edit form hydrates +
   lets you toggle memberNumber / memberStateId / role flags, Save toasts success, but
   `PersonUpdateRequest` omits them and `PUT /persons/{id}/clubs/current` is never called → **silent
   data loss**, zero edit-path test coverage.

| Gap type | Count |
|---|---|
| Below legacy / corrected-shape bar (incl. safety holes) | 4 (3 high, 1 medium) |
| Not as-you-type (touched/blur/submit only) | 11 forms |
| Validator present but no `[errors]` binding (never renders inline at all) | 8 forms / ~30 fields |
| Missing server-roundtrip as-you-type pre-check (submit-time 409 still safe) | 6 |

**Refuted (do NOT action):** "AF has no flights domain" (full domain exists); "missing-aircraft only
client-guarded" (`@NotNull`+`@Valid`+DIVE→400); "users email-duplicate needs a 409 branch" (AF has no
email-uniqueness invariant — collides only at Keycloak as 502); aircraft/club/location submit-safety
"hollow" (all hold).

## 2. The three lenses

### 2a. Below the legacy bar (most serious)

| # | Form | Field | What's wrong | Fix locus | Sev |
|---|---|---|---|---|---|
| 1 | Flight-type | FlightCode | dup → **500** not 409 | `FlightTypesExceptionHandler` (no DIVE handler — mirror `LocationsExceptionHandler.java:83`); `FlightTypesService.java:70,100,154` pre-checks name only | high |
| 2 | Flight-type | Instructor×Observer | XOR enforced at no layer | client `flight-types-edit.page.ts:300-301`; domain `FlightType.java:158-180`; DB `V3:255-289` (no CHECK) | high |
| 3 | Person edit | membership | UPDATE silently drops edits | `persons-edit.page.ts:375-382` omits; `persons.store.ts:181-200` never calls `PersonsController.java:139-144` | high |
| 4 | Club edit | clubKey | dup → 409 **mislabeled as slug** | `ClubsService.java:164-169` maps *any* DIVE → `SlugAlreadyExistsException` (must discriminate `ux_club_key` vs `ux_club_slug`) | medium |
| 5 | Profile (Account) | languageId | missing client `required` (legacy `profile.html:61` had it) | `profile-account.tab.ts:174` | medium |

> **Flight edit — dead validator (medium systemic):** `FlightValidator`/`FlightCompositeValidator`
> author the full Validate-job rule set (no-date, no-pilot, winch-operator, landings≥1, time-required)
> but are **never invoked** on any production path (`FlightsService.createFlight/updateFlight` never call
> them; javadoc admits they're for a future job/endpoint). Wire it or delete it. Not a hard safety hole
> (`@NotNull`/`@Min`/`@Valid` backstop the critical fields server-side), but the form has **zero** client
> validators and Save is never gated on validity.

### 2b. Not as-you-type (the systemic J-6b-bar miss)

Reuse vehicle: `shared/util/form/inline-validation.ts:120` `liveFieldErrors()`. Wired on
reservation/planning-edit; **absent** on: aircraft, articles, clubs, flight-types, **flights** (no
validators at all), locations (+IOP rows), persons, planning-setup, users, profile (4 tabs).

**Sub-class — validator present but NO `[errors]` binding** (silent even on submit; the slot is a
*prerequisite* for any as-you-type fix, not a substitute — `af-form-field` defaults `errors` to null):
aircraft (7 fields), article (articleInfo), flight-type (FlightCode), location (IOP rows), person
(city/mobile/memberNumber), user (phone/remarks), profile (9 fields).

> Cosmetic (all forms, low): `af-field-errors` renders the i18n **key** verbatim
> (`common.errors.required`) — no transloco pipe in the component.

### 2c. Missing server-roundtrip as-you-type pre-check (submit-time 409 CONFIRMED safe)

Aircraft (immatriculation uniqueness), Article (articleNumber), Club (clubKey — see 2a#4), Flight-type
(flightCode — 500 today), Location (ICAO + FK), User (username). Each lacks a `/validate` async leg, but
the **save path 409 holds** in every case. Already wired (reference): reservation overlap+duration,
planning (date,location) uniqueness+FK.

## 3. Per-form summary

| Form | Legacy-parity | As-you-type | Server-roundtrip | Top gap |
|---|---|---|---|---|
| Reservation edit | ✅ exceeds | ✅ wired | ✅ overlap+duration | (clean) |
| Planning-edit | ✅ exceeds | ✅ wired | ✅ uniqueness+FK | low: info maxLength client-side |
| Aircraft | ✅ exceeds | ❌ | ⚠️ immatric. submit-only | 7+6 silent fields |
| Article | ✅ exceeds | ❌ | ⚠️ articleNumber submit-only | as-you-type |
| Club | ⚠️ clubKey mislabel | ❌ | ⚠️ mislabeled | **clubKey→wrong field 409** |
| **Flight-type** | ❌ **2 holes** | ❌ | ❌ flightCode 500 | **FlightCode 500 + Instr/Obs XOR** |
| Flight | ⚠️ dead validator, 0 client | ❌ | n/a (server backstop) | dead validator + no client validation |
| Location | ✅ exceeds | ❌ | ⚠️ ICAO submit-only | IOP rows silent |
| **Person** | ❌ **membership drop** | ❌ | n/a | **silent membership data loss** |
| Planning setup | ✅ meets | ❌ | ✅ n/a | as-you-type + 2 declined better-than-legacy |
| User | ✅ exceeds | ❌ | ⚠️ username submit-only | as-you-type |
| Profile (4 tabs) | ⚠️ languageId required | ❌ | ✅ n/a | languageId required regression |

## 4. Prioritized fix backlog

**P0 — safety / data-loss (server + store + e2e each → a small form-validation-hardening journey, ~3 forms):**
1. Flight-type FlightCode 500→409: add DIVE handler discriminating `ux_flight_type_club_code` → 409
   `field=flightCode` + `findActiveByCode` pre-check.
2. Flight-type store/effect 409 discrimination on problem-detail `field` (name vs code) — required *with* #1.
3. Flight-type Instructor×Observer XOR: cross-field validator (`flight-types-edit.page.ts:300-301`) **and**
   domain guard in `FlightType.updateFlags()` (ADR-0022 #2: domain is the must-have); optional DB CHECK.
4. Person: wire membership update — call `PUT /persons/{id}/clubs/current` from `persons.store.ts`
   update with the hydrated fields; add an edit-path e2e asserting round-trip.
5. Club: distinguish `ux_club_key` from `ux_club_slug` in `ClubsService.persist()` → clean clubKey 409.

**P1 — client-parity regression:**
6. Profile `languageId` `Validators.required` (`profile-account.tab.ts:174`).
7. Flight: wire-or-delete `FlightCompositeValidator`; add client `required` on flightDate/aircraftId/pilot
   + gate Save on `form.invalid`.

**P2 — as-you-type bulk (mechanical; one shared infra `liveFieldErrors`):** aircraft, article, club,
flight-type, location (+IOP), person, planning-setup, user, profile (4 tabs). Each = import
`liveFieldErrors`, replace `ctl.touched ? ctl.errors : null`, debounce ~200ms.

**P3 — missing `[errors]` slots (prerequisite for P2 on those fields):** bind `[errors]` on the
`af-form-field` for the ~30 silent fields listed in §2b.

**P4 — server-roundtrip as-you-type pre-checks:** aircraft immatriculation, article articleNumber,
location ICAO, user username — add a non-mutating `/validate` + debounced store rxMethod (model on
reservation overlap) + merge via `asyncErrors$`/`mergeFieldErrors`. Lower priority (submit-time 409 safe).

**P5 — declined better-than-legacy / cosmetic:** planning-setup `start≤end` + `≥1 weekday` cross-field
validators; planning info `maxLength(4000)` client-side; transloco-translate the `af-field-errors` keys;
reservation/planning FK→500 DIVE→400 handlers.

**Sizing:** P0 (1–5) = safety/below-bar → a small **form-validation-hardening journey** (flight-type,
person, club). P1–P5 = `_BOYSCOUT.md` riders attaching to each form's next touch; P2/P3 (as-you-type +
slots) share one infra so they could fold into a single as-you-type sweep on a form-heavy journey.
