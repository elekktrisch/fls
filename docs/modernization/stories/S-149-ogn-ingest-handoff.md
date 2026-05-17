---
id: S-149
title: OGN ingest endpoint — per-tenant handoff with upstream maintainer
epic: E-07
status: todo
depends_on: [S-066]
acceptance:
  - Contact with the OGNAnalyser maintainer (sgacond on GitHub) is established; the new POST endpoint contract (S-066) is shared with them.
  - The maintainer commits to either (a) changing OGNAnalyser to call the new POST endpoint per AlpenFlight tenant, or (b) supporting a schema-compatible fallback documented in S-066 (C8).
  - A test ingest succeeds end-to-end: OGNAnalyser → POST /api/v1/ingest/ogn (configured for a staging AlpenFlight tenant) → flight row lands in the right tenant.
  - The handoff is per-tenant, not a global flag: each AlpenFlight tenant that wants OGN coverage points OGNAnalyser at its own ingest URL (or supplies its tenant key — refine).
estimate: M
adr_refs: []
parity_test: tests/ogn/handoff.spec.ts (new)
---

## Context
Vision C8 + R9. OGNAnalyser is upstream of AlpenFlight; its operator is independent. We don't decommission anything centrally — each tenant that wants OGN data arranges the handoff on their own schedule.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Outreach (email / GitHub issue).
- [ ] Document the per-tenant configuration on AlpenFlight's side (where the tenant admin gets their ingest URL / key).
- [ ] Stage-test the integration.
- [ ] If maintainer is unreachable: implement the schema-compatible fallback in S-066 and document the workaround.

## Notes
Per-tenant handoff is a feature of the SaaS shape — every tenant onboards on their own schedule. The legacy OGN-writes-to-DB direct path stays available for tenants that haven't flipped over yet; their data simply doesn't flow into AlpenFlight until they do.
