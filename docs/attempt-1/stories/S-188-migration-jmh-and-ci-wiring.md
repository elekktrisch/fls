---
id: S-188
title: Migration-bundle — JMH bench (FlightCrewMapper) + CI workflow wiring
epic: E-02
status: todo
depends_on: [S-185, S-187]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: false
acceptance:
  - **JMH microbench** on the `FlightCrewMapper.readEntity` path (1M synthetic rows through `map(JsonNode, PreparedStatement)`). `me.champeau.jmh` plugin (≥ 0.7.3), `src/jmh/java/` source set, `fork=1`, `profilers=['gc']`, `resultFormat='JSON'`. Pass: ≥ 200K rows/sec single-thread; ≤ 50 MB allocation/sec. Regression threshold vs committed `migration-bundle/jmh/baseline.json`: -20% throughput OR +50% alloc-rate fails.
  - `jmhCompareBaseline` Gradle task: runs JMH, parses JSON result, fails on threshold breach. CI flake mitigation: runs twice on regression; fails only if both runs exceed the threshold.
  - **CI workflow wiring.** `.github/workflows/ci.yml` adds a `migration-bundle-build` job path-filtered on `alpenflight/migration-bundle/**` that runs the module's build + unit tests + ArchUnit. Separate `migration-bundle-parity` workflow file path-filtered on `migration-bundle/**` + `flsserver/database/**` runs the parity job at normal scale on PRs and 10× nightly on `main` (`parity.scale=10` sysprop).
  - **JMH gate** path-filtered on `FlightCrewMapper.java` + `Mapper.java` + `Coercions.java` — only files whose change can move the bench. Runs `jmhCompareBaseline`.
  - JMH baseline-refresh procedure documented in module README (PR commits the new `baseline.json` with rationale in the commit body).
estimate: M
adr_refs: [0010, 0019, 0022]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Once `FlightCrewMapper` (S-185) and the parity harness (S-187) land, this story wires the JMH allocation-discipline gate + CI workflows.

## Cross-story contracts

- **Consumes:** S-185's `FlightCrewMapper`; S-187's harness for nightly parity.

## Notes

- `me.champeau.jmh` 0.7.3 chosen per S-183 refinement.
- Path-filter logic on the JMH gate: only changes to files that can move the bench trigger it, to avoid PR-cycle pollution.
- Nightly 10× parity failure opens an issue, does not block `main`.
