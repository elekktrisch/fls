# Journey roadmap

> Forward-looking only. Shipped journeys: see _SHIPPED.md + implemented/.

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
| J-20 🔨 | Demo mode — each visitor gets a private, populated sandbox club (`/demo`) — **carved 2026-08-23** | E-15 | J-2, J-3, J-5, J-15 | S-135, S-136 | N/A (greenfield) | none (new) → `/demo` |
| J-34 | Gate coverage sweep (hardening — reuses a built screen for its proof) | E-13 | — | — | N/A (infra) | none (new) |
| J-21 | Migrate-from-legacy upload wizard (all entities) | E-15 | J-0..J-10, **J-0c** | S-142, S-189, S-028 (+impl S-138/139/140/141) | all (orchestrates per-journey mappers); **reuses J-0c's legacy→migrate+Keycloak→AlpenFlight video harness** for every entity | none (new) → `/migrate` |
| J-22 | Freemium upgrade + billing | E-15 | J-21 | S-143, S-144, S-145, S-146, S-147 | N/A (greenfield) | none (new) |
| J-28 | Documentation site — user manual + architecture (infra) | E-13 | J-24 | — | N/A (docs tooling) | gh-pages docs site (manual from proof captures + C4 architecture) |

**🔨 = in flight.** All other unmarked rows are `todo`. Shipped-journey PR numbers and
done-dates live in `_SHIPPED.md`; their full carve prose lives in `implemented/`.

**Retired at carve time (operator decision 2026-07-22, `/do-plan next`):**
- **J-14 — OGN ingestion.** Dropped as a journey (never carved). Carve-time scouting found it
  mostly-headless with an inbound contract that **cannot be finalized without negotiating with the
  external OGNAnalyser maintainer** (`sgacond`, separate repo; R9 / S-149 / S-150) — so building it
  now ships speculative endpoint code, and the roadmap's own Assumption 3 sanctioned dropping it.
  Its rolled-up stories re-home: **S-088** (aircraft-DB sync) → **J-15** jobs console; **S-023**
  (UnscopedTenantContext) → **already satisfied** by `Tenants.runAs(clubId,…)` (annotation-sugar
  deferred); **S-066 / S-149** (ingest endpoint + per-tenant handoff) → **HELD** pending the
  maintainer contract (revisit as a future journey once the contract lands).

**Folded at carve time (2026-07-23, re-confirmed 2026-08-02, `/do-plan next`):**
- **J-18 — Passenger/scenic-flight registration → folded into J-17.** Carve-time analysis found discovery +
  scenic are two variants of ONE public-registration feature, not genuinely independent screens: identical
  registrant/invoice fields, `PassengerFlightRegistrationDetails ⊂ TrialFlightRegistrationDetails` (differs
  only by `SelectedDay`), identical service wiring except discovery reserves a glider + sets a trainee flag +
  uses its own email templates. The shared **S-025 public-tenant spine builds once**; both thin forms ride it
  in one green run (skill: "split only when genuinely independent features"). S-099 stamped
  `rolled_up_into: J-17`. **Escape hatch:** scenic is J-17's named deferrable tail — if the gate surfaces heavy
  unforeseen work, `/do-ship` ships discovery complete and re-files scenic as J-18 rather than half-shipping both.

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

**J-34 — Gate coverage sweep (hardening)** (filed by `/do-plan` 2026-08-23, on the J-33 retro's routing
result). About twenty riders in `_BOYSCOUT.md` are one coherent cluster: a gate that does not read its own
inputs, or a lane that reds nothing a merge depends on. Named members: `[NG-LINT-COVERS-TWO-E2E-DIRECTORIES-ONLY]`,
`[E2E-TSCONFIG-NODE10-REJECTED-BY-TS6]`, `[WEB-SCRIPTS-ARE-TYPECHECKED-BY-NOTHING]`,
`[EXTRACT-LANE-REDS-NOTHING-A-MERGE-DEPENDS-ON]`, `[ARCHUNIT-AND-NULLAWAY-DEMO-GATES-NEVER-RUN]`,
`[NIGHTLY-RUNS-ON-NO-PULL-REQUEST]`, `[COMMENT-GATE-DOES-NOT-COVER-GITHUB-DIR]`,
`[DOCKER-SKIP-TURNS-AN-MSSQL-GUARD-GREEN]`, `[GATING-LANE-SKIP-HAS-NO-GUARD]`,
`[FIXTURE-TABLE-NAMING-GUARD-SCANS-PROSE]`, `[THEME-GUARD-MISSES-PROTOCOL-RELATIVE-URLS]`,
`[CHECK-THEME-LOAD-IS-ROTTEN-AND-UNWIRED]`, `[QODANA-BUILD-FILE-BLIND-SPOT]`.
Splitting the cluster across the 40% slots of several feature journeys makes it stop being coherent, which
is why it takes the `hardening: true` type. It holds the SAME bar as any journey: **≥1 provable screen
result + a green gate**, and it may reuse a built screen for that proof. Every guard it ships must plant a
violation per input class and score the old code ([[feedback_gate_must_prove_a_red_per_input_class]]).
Not carved — carve it with `/do-plan J-34`.

## Per-journey Playwright contract (the one-line gate)

- **J-20:** A visitor with no account selects the demo call-to-action and lands on a populated `/start`. The visitor changes a flight. A second visitor gets a different seat and reads the seeded value. A system administrator's `sandbox-reset` Run-now reclaims the expired seat.
- **J-34:** A guard that was blind to an input class reds on a planted violation, and the built screen it reuses stays green.
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
| AircraftDatabaseSyncJob (S-088) | J-15 jobs console (re-homed from retired J-14) | admin screen — "run now" against a recorded OGN DDB fixture |
| AircraftStatisticReportJob (S-087) | J-10 Deliveries | screen that consumes its Excel output |
| Excel export infra (S-093/094/096) | J-7 Flight reports | real screen — first sync export consumer |
| Proffix machine client (S-029) + verification (S-080/150) | J-10 Deliveries | real screen — the API surface Proffix consumes |
| UnscopedTenantContext (S-023) | ✅ satisfied — `Tenants.runAs(clubId,…)` (used by audit + jobs) | annotation-sugar (`@SystemTenantAware`) deferred; capability exists |
| **OGN ingestion (S-066/149)** | **HELD — no journey** (retired J-14) | inbound contract is greenfield + **blocked on the external OGNAnalyser maintainer** (R9 / S-149 / S-150); revisit as a future journey once the contract is negotiated. |
| SSE channel (S-176) | J-3 dashboard | real screen — live tile updates |

No `escalate: true` — every headless item found a screen, was already satisfied, or (OGN ingestion) is HELD pending an external-maintainer contract (retired J-14).

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
3. ~~OGN (J-14) gets an invented test-only affordance.~~ **Resolved (operator, 2026-07-22):** J-14 retired — OGN ingest is greenfield + blocked on the external OGNAnalyser maintainer (S-149/S-150), so it's HELD pending that contract; S-088 re-homes to J-15, S-023 is already satisfied by `Tenants.runAs`. See the "Retired at carve time" note above.
4. Air movements (S-064) folds into J-2 (motor-aircraft tab of the same flight screen), not its own journey.
5. Reservations + scheduler (S-068/069) are one journey J-5 (two views on one route family).
6. Rules-engine internals (J-9) proven through the DeliveryCreationTest dry-run screen (legacy's actual debugging surface); highest-risk journey, gated by S-107 corpus.
7. Freemium/billing (J-22) greenfield; billing verified in provider test-mode, no real charges in the Playwright run.
8. Dashboard variants (S-166/167) and depth specs (S-101–106) are assertions added to existing journeys, not journeys.
