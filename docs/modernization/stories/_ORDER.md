# Journey roadmap

Vertical-slice execution order for the `do-*` suite. Each entry is a **journey**:
one SPA screen/route, full CRUD, driven end-to-end DB→domain→API→UI, provable by
**one green Playwright run**. This replaces the old horizontal phase order (whole-
layer stories) — that backlog still exists as `S-NNN` story files (refinement
preserved) and in git history; journeys *roll them up* by ID. The 77 stories in
`implemented/` are untouched history.

**Status:** roadmap proposed 2026-05-31 by `slice-carver` (Mode A). Journeys are
carved JIT (Mode B, `/do-plan J-NNN`) just before `/do-ship` builds them.

**Format:** `J-NNN | title (screen/route) | epic | depends_on | rolls_up | migration`

## The order

| J | Title (screen/route) | Epic | Depends on | Rolls up (todo S-NNN) | Migration | Replaces legacy |
|---|---|---|---|---|---|---|
| ✅ **J-0** | **Locations CRUD — chain bootstrap** | E-06 | — | S-062g, S-110 (+reuses impl S-049/049b/049c) | `Location` (clean-seed only; migrate→J-0b) | `masterdata/locations/` → `/locations` |
| ✅ **J-0b** | **Migration fan-out foundation** | E-02 | J-0 | S-016 (S-189 deferred→still todo) | the `(legacy_id, club_id)→new_id` fan-out subsystem (shared infra) | none (headless migration) |
| ✅ **J-0c** | **Fan-out migration parity proof (UI + video)** | E-02 | **J-0b** | S-028 (Location-scope slice), CLUB-pgcopy fix | full chain for **Location only**: legacy `flsweb` create→export→migrate+Keycloak→AlpenFlight per-club video | `Location` (real legacy→AlpenFlight) |
| J-1 | Aircraft register | E-06 | J-0, **J-0b** (for migrate-fidelity) | S-161, S-162†, S-163†, S-164† | `Aircraft` | `masterdata/aircrafts/` → `/aircrafts` |
| ✅ **J-2** | Flight list + edit forms (hot path) | E-07 | J-1 | S-061, S-062d/e/f/h/i, S-064 (+reuses impl S-062a/b/c, S-063, S-067) | `Flight`, `FlightCrew` | `flights/` + `airmovements/` → `/flights` |
| ✅ **J-2b** | **Flights hardening — new-flight visibility + edit-form validation + migration fidelity** (operator P0 #229; hardening sprint, no new screen) | E-07 | J-2, J-27 | — (#229 + FLIGHT-FIDELITY rider) | `Flight` re-verify (crew + flight-type) | hardens `/flights` (no redesign) |
| 🔨 **J-2c** | **Flights date-range filter — default visibility + working controls + styling** (operator-observed P1; `af-date-picker` binding/interaction/underline fix, no new screen) | E-07 | J-2b | — (operator bug report) | N/A (UI fix) | hardens `/flights` filter (no redesign) |
| J-3 | Pilot dashboard / home | E-07 | J-2 | S-176 (+impl S-165); S-166/167 as assertions | N/A | `main/dashboard/` → `/dashboard` |
| J-4 | Profile self-edit | E-06 | J-2 | S-182 | `Person` (self) | `profile/` → `/profile` |
| J-5 | Aircraft reservations | E-08 | J-1 | S-068, S-069 | `AircraftReservation` | `reservations/` + `reservation-scheduler/` → `/reservations` |
| J-6 | Planning days + setup | E-08 | J-5, J-2 | S-070, S-071, S-086 | `PlanningDay` | `planning/` → `/planning`, `/planningsetup` |
| ✅ **J-6b** | **Reservations & Planning hardening + inline form validation** | E-08 | J-5, J-6 | — (operator field-test polish) | N/A — hardens J-5/J-6 | `/reservations` + `/planning` polish + shared edit-form + nav shell |
| ✅ **J-7** | Flight reports + Excel export | E-07/E-11 | J-2 | S-065, S-093, S-094, S-095, S-096 | N/A (read-side) | `reporting/` → `/flightreports` |
| ✅ **J-26** | **Hardening sprint — bugfixes, UX corrections, JDBC retirement, redundancy purge** (operator-directed, no new features; order ≠ id) | cross | J-7 | — (absorbs `_BOYSCOUT.md` riders) | N/A | hardening of shipped screens |
| ✅ **J-8** | Accounting rule filters | E-09 | J-1 | S-072 | `AccountingRuleFilter` | `masterdata/accountingRules/` → `/accountingrules` |
| ✅ **J-9** | Delivery creation test (rules-engine proof) | E-09 | J-8, J-2 | S-073, S-074, S-075, S-076, S-077, S-079, S-107 | N/A (harness) | `masterdata/deliveryCreationTests/` |
| 🔨 **J-9b** | Flight-time-credit / discount sub-engine (carved 2026-06-20) | E-09 | J-9, J-4 | (split from S-074 at J-9 ship — credit balances + DiscountInPercent + over-credit 2-line split + PersonFlightTimeCreditTransaction side-effects) | `PersonFlightTimeCredit` (+ IsCurrent txn balance) — new mapper (FK PersonId→Person/J-4; nullable BalancedDeliveryId→Delivery) | rules-engine credit path (operator-deferred from J-9, 2026-06-14) |
| ✅ **J-10** | Deliveries — read-only screen (clean-seed) | E-09 | J-9 | S-078 (read half over clean-seed; migration→J-10b after J-11 ARTICLE; write→J-10b. jobs S-089/090→J-15; Proffix S-029/080/150→follow-up; S-087→reporting) | `Delivery`, `DeliveryItem` (read-only) | `masterdata/deliveries/` → `/deliveries` (read-only) |
| J-10b | Deliveries — migration + booking + write side | E-09 | J-10, J-11 | S-078 (Delivery/DeliveryItem migration — needs J-11 ARTICLE migrated first, `DeliveryItem.article_id` NOT NULL RESTRICT; + the write half — create/book/delete, gap-free delivery_number counter, Prepared→Booked state machine + 409-terminal, engine→persist) | `Delivery`+`DeliveryItem` migration, `Delivery` (write behavior) | `/deliveries` book/delete actions |
| J-27 | **Migration-fidelity sprint — drive the fanout fully green** (operator-sanctioned tech-debt, J-10 retro 2026-06-19; clears the hard fanout gate for every future migration journey) | E-02 | J-10 | — (fixes existing mappers/specs: J-0c Location render, J-8 `AccountingRuleFilter` predicate `filter_config`, J-9 migrated FlightTime filter → `article-5001`) | N/A — repairs migrated parity, no new mapper | fanout `[migration/parity]` for J-0c/J-8/J-9 |
| J-11 | Articles + Email templates | E-06 | J-0 | S-055, S-158, S-177 (+impl S-054) | `Article`, `EmailTemplate` | `masterdata/articles/`, email-templates |
| 🔨 **J-12a** | Pilot self-serve club join (carved 2026-06-23; split from J-12 — pilot screen first for a fast visible result) | E-06 | J-3, J-4 | S-177, S-178, S-179 | N/A (greenfield) | none (new) → `/join` + `/join/pending` |
| 🔨 **J-12b** | Admin join-request approval + invite robustness (carved 2026-06-24) | E-06 | J-12a | S-180, S-181 | N/A (greenfield) | none (new) → `/join-requests` |
| J-13 | System data + logs (admin) | E-06 | J-0 | S-056, S-160 | `SystemData` | `system/logs/` → `/system/logs` |
| J-14 | OGN ingestion (admin/test affordance) | E-07 | J-2 | S-066, S-088, S-023, S-149 | N/A (inbound API) | none (headless) |
| J-15 | Scheduled-jobs admin console | E-10 | J-2, J-9, J-10 | S-081, S-082, S-018, S-083, S-084, S-085, S-038, S-089, S-090 (delivery-creation + mail-export jobs re-homed from J-10) | N/A | none (admin) → `/system/jobs` |
| J-16 | Public landing + nav | E-12 | J-0 | S-133 (+impl S-097, S-157) | N/A | `main/` → `/main` |
| J-17 | Trial-flight registration | E-12 | J-16, J-1 | S-098, S-025 | `Flight` (trial subset) | `tryflight/` → `/trialflight` |
| J-18 | Passenger-flight registration | E-12 | J-16, J-1 | S-099 | `Flight` (pax subset) | `passengerflight/` → `/passengerflight` |
| J-19 | Lost-password / email-confirm landing | E-12 | J-16 | S-100 | N/A | `lostpassword/`, `confirm/` |
| J-20 | Sandbox demo | E-15 | J-2, J-5 | S-135, S-136 | N/A (greenfield) | none (new) |
| J-21 | Migrate-from-legacy upload wizard (all entities) | E-15 | J-0..J-10, **J-0c** | S-142, S-189, S-028 (+impl S-138/139/140/141) | all (orchestrates per-journey mappers); **reuses J-0c's legacy→migrate+Keycloak→AlpenFlight video harness** for every entity | none (new) → `/migrate` |
| J-22 | Freemium upgrade + billing | E-15 | J-21 | S-143, S-144, S-145, S-146, S-147 | N/A (greenfield) | none (new) |
| ✅ J-24 | Proof-video gallery (infra) | E-13 | J-0 | — | N/A (CI tooling) | gh-pages proof gallery |
| ✅ J-25 | Proof-gallery PR previews (infra) | E-13 | J-24 | — | N/A (CI tooling) | per-branch gh-pages preview |
| J-28 | Documentation site — user manual + architecture (infra) | E-13 | J-24 | — | N/A (docs tooling) | gh-pages docs site (manual from proof captures + C4 architecture) |

**✅ = done** (journey file `status: done`). Merged to `main`: J-0 (#190), J-0b (#198),
J-0c (#200), J-1 (#202), J-2 (#205), J-3 (#206), J-4 (#207), J-5 (#210), J-6 (#211),
J-6b (#213), **J-7 (#215 + stacked #217 JPA-read-model)**, **J-26 (#219, 2026-06-13 —
hardening sprint: P0 validation bugfixes + UX + ADR-0027 JDBC retirement + redundancy
purge + proof-infra riders + nightly real-idp resurrection)**, J-24 (#192), J-25 (#196).
**🔨 = in flight:** **J-8** (Accounting rule filters, carved 2026-06-13 — first E-09
journey; rides Qodana + KC-26 + the J-26 form-infra riders in its ≤40%). All other
unmarked rows are `todo`.

†S-162/163/164: backend already `implemented/`; journey re-asserts parity only.

**J-0b — Migration fan-out foundation** ✅ **DONE** (PR #198, awaiting merge; filed
2026-05-31 as a ship-time discovery during J-0). J-0's live migrate proof revealed the
core `(legacy_id, club_id)→distinct new_id` fan-out keying was entirely unbuilt — a
shared legacy row referenced by 2 clubs PK-collided on ingest. J-0b built the fan-out
subsystem and re-enabled the `@Disabled` `LocationMigrationRoundTripIT`. **Shipped shape**
(implementation-architect pass, 2026-06-01): id **derived** producer-side
(`id = uuidv5(legacy_guid, legacy_club_id)`), **fan-out-only** composite keying gated by
`EntityType.fansOut()`, the child carries its own `club_id` so `ForeignKeyResolver` keys
`(legacy_guid, club_id)` and resolves to the referencer's own-club replica; `t_location`
+ `t_inoutbound_point` each gained a `legacy_guid` column (V23/V24). Proven by two real-
Postgres ITs (`LocationMigrationRoundTripIT` + `LocationRealProducerRoundTripIT`, the
latter gating the real `assembleTarGz` tar ordering — a gap-hunter blocker fixed at the
gate). Every later journey's *migrated-data* fidelity depends on this. **Open follow-ups:**
S-189 (deferred, still `todo`); the CLUB identity-pgcopy ↔ `seedClubLegacyIdMap` collision
(no full real-producer bundle *including CLUB* runs end-to-end yet — **file before J-21**).

**J-24 — Proof-video gallery (infra)** (filed by `/do-retro` 2026-06-01 on operator
ask). J-0's pass-videos land only inside the per-run CI artifact as opaque
`page@<hash>.webm` — not glanceable. J-24 publishes a clickable gh-pages gallery
that captions each proof video with the assertion it proves (the human-parity half
of the done-bar, made reviewable). Reuses the existing `alpenflight-e2e.yml`
gh-pages pipeline; caption source is the load-bearing design choice (carve-time).

**J-25 — Proof-gallery PR previews (infra)** (filed 2026-06-01 on operator ask, at
J-24's green PR). J-24 publishes the gallery **only on merge to `main`** (publish-on-
green, to avoid N integration branches racing one gh-pages branch), so a reviewer
can't *click* it pre-merge — they download the proof artifact. J-25 adds a per-branch
gh-pages preview (`/alpenflight/proof-preview/<branch>/`) published on each proof run
+ a clickable link on the PR. This is the fork J-24's carve deliberately deferred;
the real carve-time costs are race-avoidance (per-branch namespacing) and preview
**cleanup** (reaper on PR close, else unbounded accumulation).

**J-28 — Documentation site (infra)** (filed by `/do-retro` 2026-06-24 on operator ask: architecture
diagrams + a user manual with screenshots). Shaped to honor directive 1 (working software over
comprehensive docs): documentation as a **byproduct of shipping**, not separate prose that rots. Two
outputs on a gh-pages docs site: (1) a **user manual** assembled from each journey's already-captured
proof screenshots/videos + the journey contract (the gallery captures are the source — no new manual
photography); (2) a **C4-style architecture** doc maintained alongside the ADRs. Carve-time defaults
(operator to finalize at `/do-plan`): **diagrams-as-code** (Mermaid/Structurizr rendered in CI, so they
can't drift) over hand-drawn images; gh-pages hosting like the gallery; audience = end-users (manual) +
developers/stakeholders (architecture). After J-28 ships, the **[PER-JOURNEY-DOC]** standing rider has
each feature journey contribute its manual page + diagram delta on its gate — keeping both current.

## Journey-0 — `J-0 Locations CRUD`

The thinnest already-built screen (`S-049/049b/049c` are `implemented/`), so no
feature risk competes with the chain work. Its sole job is to drag the full proof
chain into existence: **legacy-up → run the `Location` mapper (first per-journey
slice of the dissolved migration lump) to seed real data → Keycloak login → real
Playwright run + video against the new stack → wire that run into CI as a required
gate** (S-062g, S-110). `Location` is tenant-scoped, low row count, no inbound FKs
— the safest possible first mapper. Every later journey inherits the working gate
and the proven mapper pattern.

## Per-journey Playwright contract (the one-line gate)

- **J-0:** Two club admins log in via Keycloak; each sees only their own migrated Locations; create/edit/delete round-trips; cross-tenant GET 404s. (Gate + video.)
- **J-1:** Aircraft list/create/edit/delete tenant-scoped; `latestCounter` redacted for non-manager; charter aircraft visible cross-club read-only.
- **J-2:** Glider + tow + motor flight create/edit/list; time-gate blocks lock <2d / bill <3d; 412 optimistic-concurrency inline diff; paired glider↔tow save links.
- **J-3:** Pilot logs in; dashboard shows greeting + last flight + quick actions; SSE push updates a tile live; admin/sysadmin variants render role-appropriate tiles.
- **J-4:** User edits own Person across Account/Personal/Pilot/Notifications tabs; changes persist and reflect on next login.
- **J-5:** Reservation create rejects an overlapping aircraft slot; scheduler calendar renders the reservation in the right lane/time.
- **J-6:** Planning day create with assigned instructor/tow-pilot/operator; per-day reservations inline; setup wizard seeds a day; notification email lands in mailpit.
- **J-6b:** Edit form shows debounced (~200ms) inline per-field validation (client + a server validate endpoint); reservations Day/Week toggle selected-state legible + Week-mode pages by weeks with a date-range label; read-only planning-day is fully read-only with an Edit toggle; reservation Cancel from a planning-day returns to the planning-day form; dates render DD.MM.YYYY; clubadmin1 sees seeded rows + a working Users menu + no Clubs nav; Reservations nav entry present.
- **J-7:** Pick a canned report → table renders; custom report builder filters; Excel download cell-matches the legacy fixture (parity harness green).
- **J-8:** Create/edit an AccountingRuleFilter; list reflects it; filter-type dropdowns populate.
- **J-9:** `generateExampleDelivery(flightId)` previews invoice items bit-equivalent to legacy for the corpus flight; a stored DeliveryCreationTest run passes.
- **J-10:** Delivery list; Prepared→Booked transition; deleting a delivery resets affected flights' process states; Proffix-compat GET shape verified.
- **J-11:** Article + email-template CRUD; branding seven-surface preview renders; join-code rotate visible.
- **J-12a:** Pilot signs up → lands on /join → enters a club join code → pending request filed (admin emailed, SSE) → admin approves via the real endpoint → auto-Person+t_user created, KC clubId set → pilot's token refreshes → lands in-club at /start; deny+reason, withdraw+resubmit, 429 rate-limit/cooldown, 404 unknown-code all proven.
- **J-12b:** Admin /join-requests list (SSE pending-count badge) → approve modal (roles + Person picker) / deny modal (reason) drive the same backend; invite robustness recognises pre-existing Keycloak users (new / unattached / attached-elsewhere-409).
- **J-13:** Sysadmin views system data + paginated logs; append-only audit role rejects UPDATE.
- **J-14:** A guarded **test-env-only "ingest OGN sample" affordance** posts the legacy OGN contract → a flight appears in J-2's list.
- **J-15:** Admin "run job now" triggers DailyFlightValidation → flight transitions Valid; mailpit receives DailyReport; job emits started/completed events.
- **J-16:** Landing renders; nav-bar hidden on public routes by an explicit mechanism; CTAs route correctly.
- **J-17 / J-18:** Public POST creates a trial/passenger flight scoped by tenant-from-URL; unsupported tenant ID rejected; nav-bar hidden.
- **J-19:** Lost-password + confirm pages render Keycloak callback results.
- **J-20:** Anonymous session enters sandbox, edits data, nightly-reset cron wipes it.
- **J-0c (operator gate, 2026-06-01 — the fan-out UI/video proof, runs before J-1):** in the **legacy flsweb UI** create a Location with a **random name** referenced by **2 clubs** (record video) → real export → migrate **+ Keycloak provisioning of the 2 migrated club admins** → in **AlpenFlight** each club logs in and sees its OWN migrated copy of that Location, edit-isolated (record per-club video). Side-by-side legacy + AlpenFlight videos land in the gallery — this is J-0b's fan-out made UI-/video-demonstrable. Depends on **J-0b only** (Location is the sole entity), so it does NOT wait on J-1..J-10. Prereqs it must clear: a **Location-scope slice of S-028** (migrated users get no Keycloak account today) + the **CLUB identity-pgcopy ↔ `seedClubLegacyIdMap` collision** (T-10/J-0b finding — a real full bundle *including CLUB* doesn't ingest green yet). See [[feedback-demonstrable-proof-prefer-ui]].
- **J-21:** Upload an encrypted bundle → ingest provisions a trial Deployment with migrated Clubs/Flights; 72h countdown banner shows. Reuses J-0c's full-chain video harness across **all** entities (not just Location).
- **J-22:** Free tier hits a gated action → 402 → upgrade prompt → (test-mode) checkout → Deployment flips to active, auto-delete suppressed.

## Headless homing decisions

| Headless capability | Homed on | Mechanism |
|---|---|---|
| Per-journey migration mappers (the S-016/183-190 lump) | each journey's seed | real screen — every journey runs its entity mapper; J-0 proves, J-21 orchestrates |
| Rules engine (S-073–077) | J-9 DeliveryCreationTest | real screen — `generateExampleDelivery` UI dry-run |
| DeliveryCreationJob / MailExportJob (S-089/090) | J-10 Deliveries | real screen — "create deliveries" action |
| Daily validation / report / licence jobs (S-083–085) | J-15 jobs console | admin screen — "run now" |
| PlanningDayNotificationJob (S-086) | J-6 Planning | real screen — assigning crew triggers email |
| AircraftDatabaseSyncJob (S-088) | J-14 OGN | admin screen — co-located with OGN ingest |
| AircraftStatisticReportJob (S-087) | J-10 Deliveries | screen that consumes its Excel output |
| Excel export infra (S-093/094/096) | J-7 Flight reports | real screen — first sync export consumer |
| Proffix machine client (S-029) + verification (S-080/150) | J-10 Deliveries | real screen — the API surface Proffix consumes |
| UnscopedTenantContext (S-023) | J-14 OGN | first cross-club headless consumer |
| **OGN ingestion (S-066/149)** | **J-14 — INVENTED test-env-only "ingest OGN sample" button** | no product screen surfaces inbound OGN; guarded test affordance gives the inbound contract a Playwright proof. **⚠ operator-flag.** |
| SSE channel (S-176) | J-3 dashboard | real screen — live tile updates |

No `escalate: true` — every headless item found a screen or a justified test affordance.

## Platform riders (NOT journeys — attach to a journey or land on debugging pain)

- **Production infra / hosting:** S-030, S-031–S-037, S-040–S-046, S-091, S-151 → ride J-21/J-22 deploy + Phase K.
- **Observability:** S-032–S-038 → attach to J-15 + platform; pull forward only on pain.
- **Legacy-run depth coverage:** S-101→J-2/J-5, S-102→J-2, S-103→J-2, S-104→J-13, S-105→J-2, S-106→J-0/J-1 (run vs legacy first, re-attach to parity journey).
- **Test-corpus / CI infra:** S-062g+S-110→J-0 (gate bootstrap), S-111/S-108→J-7/J-2 perf, S-190→platform, S-092 (decommission legacy libs)→J-15 (last job ported).
- **Rework/doc follow-ups:** open ones (S-124–127, S-129–131, S-153) are platform hygiene, attach to nearest journey.
- **S-028 (bulk Keycloak provision):** → J-21. **S-134 (self-service signup):** `implemented/`; consumed by J-16/J-21.

## Superseded horizontal stories

The **migration lump dissolves**: S-016, S-189, S-187b/c/d, S-188, S-109 (port
full Playwright suite) → per-journey mappers + specs; no standalone migration or
"port the suite" story survives. The full roll-up assignment is the `rolls_up`
column above; stories are stamped `rolled_up_into: J-NNN` as each journey is
carved (Mode B), not eagerly (so re-grouping during adjudication stays cheap).

## Assumptions made

1. `implemented/` is authoritative — journeys rolling up a done story only re-assert its parity in the spec; they don't rebuild it. J-1/J-3/J-11/J-16 are thin precisely because their backend already exists.
2. The migration lump is bigger than four IDs — the whole S-183…S-190 / S-187x series folds into per-journey mappers. **Main grouping decision to adjudicate:** track these per-journey (current) vs as one migration epic.
3. OGN (J-14) gets an invented test-only affordance. Alternative: drop J-14's UI, prove OGN by API-level integration test as a headless rider on J-2.
4. Air movements (S-064) folds into J-2 (motor-aircraft tab of the same flight screen), not its own journey.
5. Reservations + scheduler (S-068/069) are one journey J-5 (two views on one route family).
6. Rules-engine internals (J-9) proven through the DeliveryCreationTest dry-run screen (legacy's actual debugging surface); highest-risk journey, gated by S-107 corpus.
7. Freemium/billing (J-22) greenfield; billing verified in provider test-mode, no real charges in the Playwright run.
8. Dashboard variants (S-166/167) and depth specs (S-101–106) are assertions added to existing journeys, not journeys.
