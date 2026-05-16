---
id: S-130
title: /modernize-refine — Security-plan ↔ inventory reconciliation pass
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: []
adr_refs: []
refined: false
origin: rework-meta
kind: workflow-improvement
origin_story: S-013
origin_pattern: Security-plan summary text drifts from the per-column inventory + PII catalog within the same story. The refine-step plan-summary block is authored early; later edits to the per-column inventory (or to other refinement sections) don't propagate back to the summary. Three S-013 review findings cluster on this — flight_type vs flight_cost_balance_type at-least-one-of typo, immatriculation regex claim with no regex shipped, coupon_number listed in tenant-rules.yaml PII but missing SQL COMMENT ON COLUMN.
---

## Context

S-013's review surfaced a pattern in the `## Security plan` section: the plan-summary block enumerated invariants and validations that didn't quite match what the per-column inventory in `## Design notes` (or the `tenant-rules.yaml` block in the same story) ultimately specified. The drift manifested as three review findings (all classified as `improvement`):

- **`flight_type` at-least-one-of CHECK** named in Security plan line 596 — never shipped because the actual constraint lives on `flight_cost_balance_type` (per Design notes line 267). The plan-summary text was a typo carried from an earlier draft.

- **`aircraft.immatriculation` regex CHECK** claimed in Security plan line 593 — never shipped because the per-column inventory at Design notes line 185 doesn't mandate it (only `VARCHAR(15) NOT NULL` + global UNIQUE).

- **`flight.coupon_number` flagged as PII** in `tenant-rules.yaml` (`Flights.pii_columns`) but missing the corresponding SQL `COMMENT ON COLUMN` entry that every other PII column got — the catalog and the SQL comment block drifted in separate edits.

In each case, the refinement section was internally inconsistent — the plan-summary block didn't match the per-column inventory + the SQL comment block + the YAML PII catalog. A reconciliation step at the end of `/modernize-refine` would catch this class of finding before implementation begins.

See [`S-013-schema-flights-aircraft-locations.md`](S-013-schema-flights-aircraft-locations.md#review) for the full review context.

## Acceptance criteria

- `/modernize-refine` SKILL.md gains a "Step N — reconciliation pass" at the end of the refine flow (after specialist subagents return their sections, before status flip):
  - For every column/constraint referenced in `## Security plan` § "Input validation (schema-level)" or § "PII handling": cross-check that the corresponding entry exists in `## Design notes` § "Per-table column inventory" (or its equivalent), and (where applicable) in `tenant-rules.yaml` and the SQL `COMMENT ON COLUMN` block.
  - For every PII column listed in `tenant-rules.yaml`'s `*.pii_columns`: cross-check that the `COMMENT ON COLUMN` block in the design notes includes it.
  - For every CHECK constraint named in the plan summary: cross-check that the per-column inventory specifies it.
  - Output: any mismatches surfaced as `## Open design questions` for the refiner to resolve before status flip.
- The pass is **read-only against the story file** — it doesn't auto-fix, only surfaces drift.
- A new test in `.claude/skills/modernize-refine/` (or its companion tooling) exercises the reconciliation against a fixture story file with deliberate drift, asserting the drift is flagged.

## Notes

- **Why workflow-level, not per-story**: the drift is intrinsic to the refine phase's section-by-section authoring (different specialist agents own different sections). A reconciliation pass is the natural place to catch it.
- **Coordination**: the implement skill's Step 7 body sweep (filed alongside this story as an apply-now chore PR) addresses a related but distinct pattern (post-`status: done` body drift); this story addresses pre-implementation cross-section drift in the refinement.
- **Out of scope**: refining the specialist agent prompts themselves to produce internally-consistent output — that's a deeper change with bigger blast radius. This story's reconciliation step catches what slips through.
