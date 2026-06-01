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
| **J-0** | **Locations CRUD — chain bootstrap** | E-06 | — | S-062g, S-110 (+reuses impl S-049/049b/049c) | `Location` (clean-seed only; migrate→J-0b) | `masterdata/locations/` → `/locations` |
| **J-0b** | **Migration fan-out foundation** | E-02 | J-0 | S-016, S-189 (fan-out slice) | the `(legacy_id, club_id)→new_id` fan-out subsystem (shared infra) | none (headless migration) |
| J-1 | Aircraft register | E-06 | J-0, **J-0b** (for migrate-fidelity) | S-161, S-162†, S-163†, S-164† | `Aircraft` | `masterdata/aircrafts/` → `/aircrafts` |
| J-2 | Flight list + edit forms (hot path) | E-07 | J-1 | S-061, S-062d/e/f/h/i, S-064, S-067-poly | `Flight`, `FlightCrew` | `flights/` + `airmovements/` → `/flights` |
| J-3 | Pilot dashboard / home | E-07 | J-2 | S-176 (+impl S-165); S-166/167 as assertions | N/A | `main/dashboard/` → `/dashboard` |
| J-4 | Profile self-edit | E-06 | J-2 | S-182 | `Person` (self) | `profile/` → `/profile` |
| J-5 | Aircraft reservations | E-08 | J-1 | S-068, S-069 | `AircraftReservation` | `reservations/` + `reservation-scheduler/` → `/reservations` |
| J-6 | Planning days + setup | E-08 | J-5, J-2 | S-070, S-071, S-086 | `PlanningDay` | `planning/` → `/planning`, `/planningsetup` |
| J-7 | Flight reports | E-07/E-11 | J-2 | S-065, S-093, S-094, S-095, S-096 | N/A (read-side) | `reporting/` → `/flightreports` |
| J-8 | Accounting rule filters | E-09 | J-1 | S-072 | `AccountingRuleFilter` | `masterdata/accountingRules/` → `/accountingrules` |
| J-9 | Delivery creation test (rules-engine proof) | E-09 | J-8, J-2 | S-073, S-074, S-075, S-076, S-077, S-079, S-107 | N/A (harness) | `masterdata/deliveryCreationTests/` |
| J-10 | Deliveries (invoice drafts) | E-09 | J-9 | S-078, S-080, S-089, S-090, S-150, S-029, S-087 | `Delivery`, `DeliveryItem` | `masterdata/deliveries/` → `/deliveries` |
| J-11 | Articles + Email templates | E-06 | J-0 | S-055, S-158, S-177 (+impl S-054) | `Article`, `EmailTemplate` | `masterdata/articles/`, email-templates |
| J-12 | Club join / invite flow | E-06 | J-3, J-4 | S-178, S-179, S-180, S-181 | N/A (greenfield) | none (new) → `/join` |
| J-13 | System data + logs (admin) | E-06 | J-0 | S-056, S-160 | `SystemData` | `system/logs/` → `/system/logs` |
| J-14 | OGN ingestion (admin/test affordance) | E-07 | J-2 | S-066, S-088, S-023, S-149 | N/A (inbound API) | none (headless) |
| J-15 | Scheduled-jobs admin console | E-10 | J-2, J-9 | S-081, S-082, S-018, S-083, S-084, S-085, S-038 | N/A | none (admin) → `/system/jobs` |
| J-16 | Public landing + nav | E-12 | J-0 | S-133 (+impl S-097, S-157) | N/A | `main/` → `/main` |
| J-17 | Trial-flight registration | E-12 | J-16, J-1 | S-098, S-025 | `Flight` (trial subset) | `tryflight/` → `/trialflight` |
| J-18 | Passenger-flight registration | E-12 | J-16, J-1 | S-099 | `Flight` (pax subset) | `passengerflight/` → `/passengerflight` |
| J-19 | Lost-password / email-confirm landing | E-12 | J-16 | S-100 | N/A | `lostpassword/`, `confirm/` |
| J-20 | Sandbox demo | E-15 | J-2, J-5 | S-135, S-136 | N/A (greenfield) | none (new) |
| J-21 | Migrate-from-legacy upload wizard | E-15 | J-0..J-10 | S-142, S-189, S-028 (+impl S-138/139/140/141) | all (orchestrates per-journey mappers) | none (new) → `/migrate` |
| J-22 | Freemium upgrade + billing | E-15 | J-21 | S-143, S-144, S-145, S-146, S-147 | N/A (greenfield) | none (new) |
| J-24 | Proof-video gallery (infra) | E-13 | J-0 | — | N/A (CI tooling) | gh-pages proof gallery |
| J-25 | Proof-gallery PR previews (infra) | E-13 | J-24 | — | N/A (CI tooling) | per-branch gh-pages preview |

†S-162/163/164: backend already `implemented/`; journey re-asserts parity only.

**J-0b — Migration fan-out foundation** (filed 2026-05-31, ship-time discovery during
J-0). J-0's live migrate proof revealed the core `(legacy_id, club_id)→distinct new_id`
fan-out keying is entirely unbuilt — a shared legacy row referenced by 2 clubs
PK-collides on ingest. J-0 narrowed to a clean-seed real chain; J-0b builds the
fan-out subsystem (producer per-replica id mint + a `t_location.legacy_guid` Flyway
column + composite `(legacy_guid, club_id)` id-map keying — the **shared** mechanism
every tenant-scoped migration uses) and re-enables the `@Disabled`
`LocationMigrationRoundTripIT`. The migrate-half foundation already landed on J-0's
branch (reference-FK resolve, InOutboundPoint mapper, producer SELECT bindings).
J-0b wants an `implementation-architect` design pass on the keying approach before
build. Every later journey's *migrated-data* fidelity depends on J-0b.

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
- **J-7:** Pick a canned report → table renders; custom report builder filters; Excel download cell-matches the legacy fixture (parity harness green).
- **J-8:** Create/edit an AccountingRuleFilter; list reflects it; filter-type dropdowns populate.
- **J-9:** `generateExampleDelivery(flightId)` previews invoice items bit-equivalent to legacy for the corpus flight; a stored DeliveryCreationTest run passes.
- **J-10:** Delivery list; Prepared→Booked transition; deleting a delivery resets affected flights' process states; Proffix-compat GET shape verified.
- **J-11:** Article + email-template CRUD; branding seven-surface preview renders; join-code rotate visible.
- **J-12:** Pilot enters a join code → request created → admin approves → auto-Person created → pilot lands in-club.
- **J-13:** Sysadmin views system data + paginated logs; append-only audit role rejects UPDATE.
- **J-14:** A guarded **test-env-only "ingest OGN sample" affordance** posts the legacy OGN contract → a flight appears in J-2's list.
- **J-15:** Admin "run job now" triggers DailyFlightValidation → flight transitions Valid; mailpit receives DailyReport; job emits started/completed events.
- **J-16:** Landing renders; nav-bar hidden on public routes by an explicit mechanism; CTAs route correctly.
- **J-17 / J-18:** Public POST creates a trial/passenger flight scoped by tenant-from-URL; unsupported tenant ID rejected; nav-bar hidden.
- **J-19:** Lost-password + confirm pages render Keycloak callback results.
- **J-20:** Anonymous session enters sandbox, edits data, nightly-reset cron wipes it.
- **J-21:** Upload an encrypted bundle → ingest provisions a trial Deployment with migrated Clubs/Flights; 72h countdown banner shows.
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
