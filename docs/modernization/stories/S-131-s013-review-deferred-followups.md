---
id: S-131
title: S-013 deferred review findings — test-plan drift + test pins + design-notes residue
epic: E-02
status: todo
estimate: S
parity_test: none
depends_on: [S-013]
adr_refs: []
refined: false
origin: rework
origin_story: S-013
origin_finding: 2 blockers (story-body Test plan drift the rework body sweep didn't reach) + 13 improvements (largest cluster: 3 cross-tenant / ownership invariants live as COMMENT ON COLUMN prose only without test pin; design-notes residue + Aircrafts.ride_through_targets missing in live YAML) + 7 nudges. Operator chose finalize-with-defer-all rather than another rework→review loop on S-013.
---

## Context

S-013's second review (post-rework) returned `blockers: 2` — both residual story-body doc-drift in the `## Test plan` section that the rework's body sweep didn't reach. The PR itself was clean: zero code blockers, zero security blockers, `./gradlew check` green, CI green on `next build` + legacy builds. Operator chose to finalize S-013 with the open findings deferred here rather than run another rework→review cycle.

See [`S-013-schema-flights-aircraft-locations.md`](implemented/S-013-schema-flights-aircraft-locations.md#review) for the full review context (the `[deferred → S-131]` annotations on each bullet point back here).

## Acceptance criteria

All findings below resolved (address-now / accept-with-rationale / further-deferred per the implementer's call). Categorized by severity and substance.

### Blockers (test-plan ↔ test-name drift)

- [ ] Story file `## Test plan` line 733: `location_type_seeded_17_canonical_values` → `location_type_seeded_6_canonical_values` (matches shipped test).
- [ ] Story file `## Test plan` line 745: "parameterized over 7 tenant-scoped tables" → "parameterized over 3 direct tenant-scoped aggregate roots (flight, flight_type, article)".

### Improvements

- [ ] **Live `Aircrafts` yaml entry missing `ride_through_targets`** — `next/database/tenant-rules.yaml` Aircrafts override. AC line 34 + design-notes example line 413 promise the field. Add `ride_through_targets: [Persons, Locations, Clubs]`.
- [ ] **Design-notes example block: `Aircrafts.ride_through_targets` lowercase singular** — story line 413 reads `[person, location, club]`; flip to `[Persons, Locations, Clubs]` to match the PascalCase-plural convention of the surrounding entries. (Bundle with the live-YAML fix above.)
- [ ] **`Flights` design-notes yaml-block includes `kind: tenant-scoped` while real YAML deliberately omits it** — story line 423 vs `tenant-rules.yaml:322-344`. Remove the `kind:` line from the design-notes example + add a one-line inline note "(kind: deliberately omitted — see classifier note in tenant-rules.yaml:339)" so the example matches reality.
- [ ] **`Flights.pii_columns` test does not pin `coupon_number` absence** — `TenantCatalogYamlTest:128-135`. Rework removed `coupon_number` from `Flights.pii_columns`; test only asserts the 5 free-text columns are present. Add `.doesNotContain("coupon_number")` with a one-line rationale.
- [ ] **`Aircrafts.pii_columns` test does not pin absence of sensitive columns** — `TenantCatalogYamlTest:118-126`. Same defensive-test-pinning pattern as the coupon_number improvement.
- [ ] **Aircraft ownership-exclusivity invariant lives as `COMMENT ON COLUMN` only — no test pin** — `V3:735-736`. Load-bearing contract for S-022's service-layer check (one of `owner_club_id` / `aircraft_owner_person_id` NOT NULL or both NULL; never both set). Add a `pg_description` assertion in `FlightBaselineIntegrationTest` that the comment contains the key phrases ("Exclusive with aircraft.owner_club_id", "NEVER both set", "S-022").
- [ ] **4× `club.default_*_flight_type_id` cross-tenant invariant pinned by comment only** — `V3:742-749`. Same shape; parameterized `pg_description` test asserting "operating_club_id MUST equal this club.id" appears on all 4 columns.
- [ ] **Security plan §"Cross-tenant leakage" still says `flight.operating_club_id denormalized from aircraft.operating_club_id`** — story line 627. Stale post-2026-05-16 cross-tenant amendment. Update to "set per-flight by operator; NOT denormalized; invariant enforced at S-022."
- [ ] **Migration header item (c) references superseded denormalization framing** — story line 104. Same fix as the leakage section.
- [ ] **`§Risks` reads "tests assert >= N computed at runtime"** — story line 786. Replace `>= N` with `>= 3` (matches Migration shape).
- [ ] **3 new provocation tests share identical begin-txn → catchThrowable → assert-23514 → rollback shape with no helper** — `FlightBaselineIntegrationTest.java:632-799`. Extract a `withProvocation(Connection, ThrowingRunnable)` or similar helper before a 4th provocation test cargo-cults the pattern.
- [ ] **`flight_crew_has_no_created_modified_audit_columns` inline comment restates the constant's Javadoc** — `FlightBaselineIntegrationTest.java:291-292`. Remove body comment; method name + Javadoc are sufficient.
- [ ] **S-129 body references a specific SQL line number `V3...sql:623-624`** — `S-129-...md:105`. Per no-ephemeral-refs spirit, line numbers in shipped SQL files rot when subsequent migrations alter them. Replace with prose description of column + constraint.

### Nudges (low-impact / cosmetic)

- [ ] S-130 cross-ref uses bare anchor `#review` — fragile if heading renamed.
- [ ] `flight_aircraft_type_id_value_3_rejected_by_check` test docstring redundantly says "Locale-independent" while assertion uses `Locale.ROOT`.
- [ ] S-130 story title says "Security-plan ↔ inventory" but body scopes broader (any refinement-section drift).
- [ ] `TenantCatalogYamlTest.flight_tenant_scope_precondition_met` uses `java.util.Locale.ROOT` fully-qualified inline; surrounding class imports `java.util.Locale` style — pick one.
- [ ] forbidden-migration-patterns regex-terminology comment phrasing is inverted (mentions `\B` boundary where it should explain underscore is `\w` so `aircraft\b` excludes `aircraft_type`).
- [ ] `TenantCatalogConsistencyTest` JavaDoc broadened to "V2 + V3"; flag for class-refactor boundary when S-014 extends.
- [ ] `S013_TABLES` constant in `FlightBaselineIntegrationTest` orders historically rather than by aggregate-composition order.

## Notes

- **Why a single defer-bucket story, not 22 separate stories**: every finding is small (1-10 lines); collectively they cluster around test-pinning + doc-drift cleanup; single-PR makes sense.
- **Why deferred rather than fixed in S-013's PR**: S-013 is already large (12 commits, +3230/-93). The 2 blockers are doc-drift (no code defect, no security defect, no shipped-bug); deferring avoids another review cycle on a PR that's substantively done. The 13 improvements are non-load-bearing for the schema's correctness — they're maintenance debt that's safer paid in a focused follow-up.
- **Priority hint**: the 3 cross-tenant-invariant test pins (aircraft ownership-exclusivity + 4× club.default_*_flight_type_id) are the highest substantive value here. The rest are cosmetic doc-cleanup. If this story gets split, those three test-pin items should land first.
- **S-022 dependency**: when S-022 (JPA wiring + @TenantId resolver) lands, it'll consume the column-comment contracts pinned in this story. Ideally this story merges before S-022 so the test pins protect the contracts S-022 reads.
