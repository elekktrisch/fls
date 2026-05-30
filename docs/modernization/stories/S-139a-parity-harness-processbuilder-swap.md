---
id: S-139a
title: Parity oracle harness — swap in-process producer for ProcessBuilder invocation of migration-tool-all.jar
epic: E-02
status: todo
depends_on: [S-139, S-187]
integration_base: integration/migration
origin: scope-split
origin_story: S-187
refined: false
acceptance:
  - **Producer side spawns `migration-tool-all.jar`.** Replace `ProducerHarness`'s in-process invocation of `Mapper.writeNdjson` with `ProcessBuilder` invoking the shadowJar from S-139. Stdin = legacy connection params; stdout = the tar.gz bundle bytes; stderr → captured for failure reporting.
  - **No behavioural delta** on the same seeded fixture. Existing happy-path round-trip assertions (S-187 + S-187a) pass byte-for-byte under the swapped producer; nightly 10× passes too.
  - **Walltime budget unchanged.** The swap MUST NOT regress PR-gated ≤ 5 min / nightly ≤ 30 min.
  - **Process-failure path** distinguishable from a parity miss: a non-zero exit + non-empty stderr fails the harness with `producer process failed: <exit>` before any diff runs.
estimate: S
adr_refs: [0019, 0022]
---

## Context

Filed from [S-187](S-187-migration-parity-oracle-harness.md) Design notes — "In-process producer is the temporary affordance. Harness wires producer side directly until `:migration-tool:shadowJar` (S-139) lands". Once the JAR exists, the harness should exercise the production codepath end-to-end.

## Cross-story contracts

- **Consumes:** S-139's `migration-tool-all.jar` artifact + producer CLI surface; S-187's `ProducerHarness` + `BundleStream`.
- **Produces:** no new API surface — this swap is internal to the parity harness.

## Notes

- Reference: S-187 Design notes section "Sibling task for S-139 shadowJar swap".
- Add a flag to gate the swap (`-Dparity.producer=process` vs `-Dparity.producer=inprocess`) for the first PR cycle so a producer-side regression can be triaged against the known-good in-process path without reverting.
