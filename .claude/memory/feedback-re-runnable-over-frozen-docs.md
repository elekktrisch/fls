---
name: feedback-re-runnable-over-frozen-docs
description: For modernization extraction/parity tooling — prefer re-runnable scripts producing JSON over giant frozen Markdown reference docs
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5c941d23-6332-4088-a757-9c95e2f2728c
---

For data-extraction / parity / inventory tooling in the modernization workflow, prefer **a re-runnable script that re-exports from the seeded legacy DB, producing JSON** over **a giant frozen Markdown reference doc** that captures everything statically.

**Why:** Re-runnability keeps the artifact fresh — every change to the source DB (or every test run against the seeded DB) re-produces the data. A 5K-10K-line Markdown doc rots the moment the legacy DB shifts, AND duplicates information the script already knows how to emit. Frozen MDs become "the lie" everyone treats as truth.

**How to apply:**
- When a story's refinement leans heavily on a rendered Markdown reference doc, default to: small MD (curation-bits only — sacred cows, PII reasoning, source-of-truth precedence, runbook) + tool that re-emits JSON / data on demand.
- **Don't commit the JSON output either.** It's ephemeral — re-export from the seeded legacy DB whenever data is needed. Gitignore all of `raw/`.
- The seeded legacy DB (the FLSTest fixture loaded into a Docker SQL Server) is the canonical source the tool re-reads from. Operators / CI start a fresh container and run the tool when they want fresh data.
- Downstream consumers re-run the tool — they do NOT read frozen MD or frozen JSON.
- The MD is for humans reasoning about modernization decisions (sacred cows, PII narrative, runbook); the tool is for tooling.
- **Implement the tool as a small Spring Boot app, NOT shell + Python + bash verifiers.** Justified by: easier to maintain (one runtime, Gradle build, JUnit), easier to test (Testcontainers + JUnit cover the verifier role natively — no parallel bash-verifier corpus needed), and unifies the lifecycle with downstream stories that extend it (S-016 transformation-into-Postgres rides the same module). Tests REPLACE the verifier set; do not write both.

**Concrete origin:** S-010 refinement proposed a 5K-10K-line `legacy-baseline.md` + four committed allow-listed `raw/*.json` files + a Python/shell extraction script + ~20 bash verifiers. User overrode mid-implement, four times: (1) "no need to write down everything in an md document. rather opt for re-exporting from the seeded legacy db using the script to be developed"; (2) "also, no need to commit raw json files to git. we just re-export from the legacy-db if data is needed"; (3) "drop the whole verifier-script concept too... a small spring-boot app would be easier to maintain and test for the database export and transformation into postgres"; (4) "for tests, please only write integrationtests that connect to the actual mssql database, no need to set up any mocking."

## Test philosophy for this stack

When testing DB-touching tooling in `next/`:
- **Integration tests only.** No unit-test layer, no in-memory DB emulation (H2-as-SQL-Server etc.), no mocking of `DataSource` / `JdbcTemplate` / `JdbcOperations`.
- **Real DB via Testcontainers.** `org.testcontainers:mssqlserver` for SQL Server-touching code; `org.testcontainers:postgresql` for Postgres-touching code.
- **One test class per integration surface**, not separate "unit" vs "integration" tiers. Multiple `@Test` methods inside it amortize the container boot cost.
- **Observable behavior, not internals.** Test what the tool emits / does, not internal helpers. If a helper isn't reachable from an integration test, that's a sign it's accidental surface area — fold into the caller or assert via the integration's observable side-effects.
- **No mocking, ever, for this stack.** Even helper utilities (arg parsers, file scanners, SQL guards) are exercised via the integration test path or dropped from the test corpus.

Related: [[fls-modernization-workflow]]
