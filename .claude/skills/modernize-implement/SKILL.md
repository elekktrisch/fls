---
name: modernize-implement
description: Phase 6 — implement one refined story end-to-end. Creates a GitHub issue, writes parity tests first (TDD), commits per work-package, pushes at green boundaries, monitors CI, closes the issue. Trigger: /modernize-implement S-NNN.
---

# Phase 6 — Story Implementation

You are running phase 6 of the modernization workflow. Your job is to take **one** refined story (S-NNN) and ship it: write the code per the architect's design notes, write the tests per the QA plan, honor the security and performance plans, run the full test suite green, and update the story's status to `done`.

This is the only phase that produces code. Everything before it has been planning.

## Preconditions (verify before doing any work)

1. The argument is a single story ID `S-NNN`. If missing, ask.
2. The story file exists at `docs/modernization/stories/S-NNN-*.md` (top-level). If not found there, also check `docs/modernization/stories/implemented/S-NNN-*.md`:
   - If found in `implemented/`: refuse with "Story S-NNN is already finalized (in stories/implemented/). Its code has shipped on `main`. Re-implementing is not supported by this skill; if you genuinely need to rework it, copy the file back to stories/ first."
   - If not found in either location: bail.
3. The story has `refined: true` in its frontmatter. If not, **bail** with: "Story not refined. Run `/modernize-refine S-NNN` first."
4. The story has `status: todo` (not `in_progress`, not `done`, not `blocked`). If `in_progress`, ask whether to resume. If `done`, refuse. If `blocked`, refuse and surface the block reason.
5. Every story in `depends_on` has `status: done` **AND its PR is merged into `main`** (`gh pr view <PR> --json state` returns `MERGED`, or the story has no `github_pr:` because it predates the PR workflow / used the fallback). A `done` story whose PR is still open means its code isn't on `main` yet — bail with: "Dependency S-NNN is done but its PR #<N> isn't merged. Merge it first, then resume." Resolve dependency story files via the two-step glob (top-level then `implemented/`) — finalized dependencies live in `implemented/`.
6. The working tree is clean (no uncommitted changes outside of test snapshots / lockfiles you yourself will produce). If dirty, ask before proceeding — uncommitted changes from a half-done previous story are a footgun.
7. Current branch is `main` (or the configured default). The skill creates a story branch off `main` at Step 2 — if you're already on a feature branch from another story, bail.

These are the only legitimate blocking conditions. Everything else is derivable.

## GitHub issue lifecycle (autonomous within this skill)

When the operator invokes `/modernize-implement S-NNN`, the skill creates a real GitHub issue mirroring the story and tracks status against it. The markdown story file under `docs/modernization/stories/` remains the **canonical design document**; the GitHub issue is the **status tracker + commit-message ref target**.

### Issue creation

At Step 2 (status flip to `in_progress`), if the story frontmatter has no `github_issue:` field yet:

1. Run `gh issue create` with:
   - **Title:** `S-NNN: <story title verbatim from frontmatter>`. The story ID stays visible in the issue title for cross-reference; the issue body links back to the canonical MD path.
   - **Body:** synthesized from the story file — the `## Context` section verbatim, the acceptance criteria as a markdown checklist, the story estimate + depends_on + parity_test, and a permalink to the story MD file. End with: "_Tracking issue. Canonical design in the MD file. Status of this issue tracks implementation._".
   - **Labels** (best-effort — apply if they exist, skip silently if not): `story`, `epic/<E-NN>` (from frontmatter), `size/<estimate>` (e.g. `size/M`), `status/in-progress`. Don't auto-create missing labels — the operator's label taxonomy is their choice.
2. Capture the issue number from `gh`'s stdout (`gh issue create --json number -q .number`).
3. **Stamp the story frontmatter** with `github_issue: <number>`. This persists across interruption: a later re-invocation of `/modernize-implement` on the same story reuses the issue instead of creating a duplicate.

If the frontmatter already has `github_issue: N`:

- Verify the issue is still open via `gh issue view N --json state`.
- Open + story `in_progress`: resume.
- Closed + story `done`: not your case — the story shouldn't be eligible for `/modernize-implement` re-entry.
- Closed + story not `done`, or open + story `done`: state mismatch — escalate per Step 5.

### Issue comments — milestones only, not per-commit

Substantive milestones get a comment; routine commits do not. The commit log is already linked to the issue by `#N` references; chatty per-commit comments dilute the signal.

Comment-worthy events:

- **Once at parity-strategy lock-in** (after Step 2.5 picks the layer): `Parity strategy: <layer> — <one-sentence rationale>`.
- **On each CI failure** (per the Commit and push policy's CI failure handling): `CI failed on push <sha>: <run url> — <one-line cause>`. Followup fixes are not separately commented; only the failure is.
- **Once on completion** (Step 7, before the issue auto-closes): a final summary comment paralleling the operator-facing done report — parity layer used, commit count, CI run outcomes.

### Issue closure

The **final commit** (Step 7's status flip) includes `Closes #N` in the **body**. GitHub auto-closes the issue when that commit reaches the default branch (in a feature-branch + PR workflow, on PR merge).

After the final push:

- Watch the issue's state via `gh issue view N --json state`.
- If still open after CI green + push success: manually close (`gh issue close N`) and apply the `status/done` label if it exists.
- If closed already (auto-close fired): apply the `status/done` label.

Do NOT delete issues. Mistakes are fixed forward — reopen + clarifying comment, never `gh issue delete`.

### Fallback when GitHub is unavailable

If any of these is true — `gh auth status` fails, `git remote -v` shows no GitHub remote, or `gh issue create` returns non-zero — **skip the issue lifecycle entirely**:

1. Don't stamp `github_issue:` on the frontmatter.
2. Use **story-ID-prefixed commit messages** instead (`S-NNN: <summary>` per the secondary policy below).
3. Skip issue comments and `Closes #N` in the final commit.
4. Document the skip in the done report (Step 7).

The operator catches up later by creating issues retroactively if they want the tracker. This is symmetric with the CI fallback — same trigger condition, same "go local-only" outcome.

## Feature branch + draft PR lifecycle (autonomous within this skill)

Each story runs on its own feature branch with a draft PR opened at first push. The PR is the artifact `/modernize-review` anchors to (it's where CI runs and where the operator merges from after review). Trunk-direct pushes are reserved for the fallback case below.

### Branch creation

At Step 2 (alongside status flip + issue creation):

1. Derive the branch name from the story file: `story/<story-id-with-slug>`. The story file is `docs/modernization/stories/S-NNN-<slug>.md`; the branch is `story/S-NNN-<slug>` (e.g. `story/S-058-flight-validator-port`).
2. If the branch already exists locally or on remote (story resumption): `git checkout story/S-NNN-<slug>`. Don't re-create.
3. If it doesn't exist: `git checkout -b story/S-NNN-<slug>` off `main` (or the configured default branch). Bail if `main` is dirty.
4. The initial `#N: start` commit lands on this branch, not on `main`.

### Draft PR creation

At the **first push** (the backend-slice push per the push-timing policy below):

1. `git push -u origin story/S-NNN-<slug>`.
2. `gh pr create --draft --base main --head story/S-NNN-<slug> --title "S-NNN: <story title>" --body "<body>"`.
3. PR body:
   - First line: `Closes #N` (links to the tracking issue; auto-closes it on merge).
   - One short paragraph: "Implements story S-NNN. Design in `docs/modernization/stories/S-NNN-<slug>.md`."
   - Acceptance criteria as a markdown checklist (mirrors the issue).
   - Parity strategy line (filled in after Step 2.5).
4. Stamp the story frontmatter with `github_pr: <number>` for later resume + review.
5. If the PR already exists (resumption with `github_pr:` set): verify it's still open + draft via `gh pr view`. Reuse.

### Ready-for-review at done

At Step 7 (after the final commit + push + CI green):

1. `gh pr ready <PR>` — flips draft → ready-for-review. This is the signal to operator + `/modernize-review` that the story is mergeable.
2. The PR auto-closes the issue on merge (because of `Closes #N` in the body). The skill does **not** merge — operator owns that.

### Fallback when GitHub or branch workflow is unavailable

Symmetric with the issue + CI fallbacks. Skip the branch + PR lifecycle entirely if any of:
- `gh auth status` fails.
- `git remote -v` shows no remote.
- The operator's repo policy is trunk-only (heuristic: no `.github/branch-protection.yml`, no recent PRs; pragmatically — only skip if `gh pr create` fails on first attempt).

In fallback: commit + push directly to whatever branch was checked out (the legacy trunk behavior). Don't stamp `github_pr:` on the frontmatter. Document the fallback in the done report.

## Commit and push policy (autonomous within this skill)

This skill **commits and pushes autonomously**. The operator pre-authorizes both by invoking `/modernize-implement` — that invocation is the consent. Outside this skill the general "ask before push" rule still applies; inside it, the operator gets a stream of commits + CI runs as the story progresses.

### Commit grouping

Commit per **work package**, not per file. Default work-package boundaries within a story (most stories use 4-7 of these, not all):

1. **Red parity / acceptance tests** (from Step 2.5) — committed first, before any production code. Tests fail for the right reasons; the diff is test files only.
2. **DB migration** — `V*__name.sql` alone. Flyway migrations are append-only; they merit their own diff for reviewer focus.
3. **Backend slice** — entity + repository + service + controller for the story's main domain, plus the unit tests for the slice, in one commit. Security annotations and validation rules live here.
4. **Frontend slice** — Signal Store + components + route + component tests, consuming the regenerated TS client from the backend slice's OpenAPI output.
5. **Test-passing iterations** — each iteration that turns a failing test green is its own commit if it required a real code change. Don't commit "fix typo" / "reformat" alone; fold those into the next meaningful commit.
6. **Performance / security final pass** — index additions, fetch strategies, audit hooks. Often empty if the prior commits already incorporated them.
7. **Story status update** — flip `status: done` + `done_at` in the story file. Final commit.

For an S-estimated story, 1-3 commits is typical. For M, 3-5. For L, 5-8. **More than 10 commits in one story suggests over-fragmentation** — merge tightly-related ones into a single commit before pushing.

### Commit message format

**GitHub-issue-number prefix** (`#N: <summary>`) + short imperative summary. The issue's title carries the `S-NNN` story ID; commits link to the issue, not the MD file directly. ≤ 72 characters in the title; body only when something non-obvious deserves explanation. Co-authored-by trailer per the harness default.

```
#42: red MockMvc + Actuator integration tests
```

```
#42: Flight entity + tenant resolver + create endpoint

Validator port deferred to its own commit per the test-plan ordering.
```

```
#42: port FlightValidator + start-type-specific arms

Mirrors ValidateFlightBasics at FlightService.cs:985-1136.
22 unit tests now green.
```

**Final commit uses `Closes #N` in the body** so GitHub auto-closes the tracking issue when the commit reaches the default branch:

```
#42: mark done

Closes #42
```

**Fallback when GitHub is unavailable** (per the GitHub issue lifecycle's fallback): use the story-ID prefix instead — `S-NNN: <summary>`. No `Closes` keyword; the issue doesn't exist.

**No Conventional Commits prefix** (`feat:` / `fix:` / etc.) in either mode. Every commit in this workflow is a feature commit by construction; the prefix is noise. The `#N` / `S-NNN` prefix gives the traceability instead.

### Push timing

Push at **work-package boundaries that are locally green** — not after every commit. Coding cadence:

- Commit 1 (red tests): **don't push** — the suite is red on purpose; CI would just confirm the red and burn a run.
- Commit 2 (DB migration): **push only if it stands alone** (rare); usually batched with the backend slice.
- Commit 3 (backend slice + tests green): **first push.** `git push -u origin story/S-NNN-<slug>` → **open the draft PR** per the Feature branch + draft PR lifecycle section. CI runs server tests + dependency checks on the PR.
- Commit 4 (frontend slice + tests green): **push.** CI runs web tests + e2e.
- Commit 5 (perf / security final): **push if it touches anything non-trivial.** Empty pass → no push.
- Commit 6 (status update): **push** + `gh pr ready <PR>` to flip draft → ready-for-review. Final.

Between pushes, dispatch the CI watch in the background and continue with the next work-package's coding. The Bash tool's `run_in_background: true` is the mechanism:

```
gh run watch --exit-status <run-id-from-most-recent-push>
```

This blocks until CI completes; backgrounded, it notifies on completion (success or fail). The foreground work-package proceeds in parallel.

### CI failure handling

When the backgrounded `gh run watch` returns non-zero:

1. **Stop the foreground work-package immediately.** Don't let further commits stack on top of a broken-CI state — that hides the regression and complicates the diff.
2. **Comment on the tracking issue** with `CI failed on push <sha>: <run url> — <one-line cause>` (skip in GitHub-fallback mode).
3. **Diagnose** via `gh run view --log-failed <run-id>`. Failures fall in three buckets:
   - Test that passed locally but fails on the runner (timing, OS differences, missing fixture). Fix in a new commit.
   - Regression: a test that was previously green is now red. Same Step 5 escalation trigger as local regressions.
   - Infrastructure: CI config wrong, dependency conflict, runner-specific failure. Often unrelated to the story; document and escalate per Step 5.
4. **Fix in a new commit, push, re-watch.** Naming convention: `#N: fix CI — <one-line cause>` (or `S-NNN: fix CI — <cause>` in fallback mode). No separate issue comment per fix — the fix commit is itself the trail.
5. **Resume the foreground work-package only after CI is green.** Don't leave a stack of commits behind a red CI build.

If CI fails twice for the same root cause after fix attempts, **escalate per Step 5** — the issue is structural, not solvable by another local guess.

### When CI is not configured (graceful fallback)

If any of these are true — `gh` is not installed; the repo has no remote; no GitHub Actions workflow exists yet; `git push` returns "no upstream branch" — **skip the push and CI-watch entirely**, continue committing locally, and document the skip in the done report (Step 7). The operator picks up the un-pushed commits.

## How to implement

### Step 1 — Load the full spec

Read in parallel:
- The story file in full (frontmatter + body + all refinement sections).
- Every ADR listed in `adr_refs`.
- The legacy code paths cited in the acceptance criteria + design notes (file:line references — open them, don't paraphrase).
- The `parity_test` reference if any.
- The `00-seed.md` sacred cows.

Skim is not enough. The refinement sections give you what to build; the legacy code gives you the *behavior you must match*. Read both with the same attention.

### Step 1.4 — Speculative-refinement freshness check

If the story's frontmatter has `refined_speculative: true`, the refinement was done ahead of implement time (via `/modernize-refine-ahead`) and may be stale. **Re-refine if any of:**

- `refined_speculative_at` is older than 14 days.
- Any ADR in `adr_refs` has been modified (`git log -1 --format=%cI docs/modernization/adrs/NNNN-*.md`) after `refined_speculative_at`.
- Any story in `depends_on` has `merged_at` later than `refined_speculative_at`.

If re-refine is needed: invoke `/modernize-refine S-NNN` (JIT, single-story flow) before proceeding. The JIT re-refine flips `refined_speculative: false` and updates `refined_at`. Then continue.

If the staleness check is clean: proceed with the speculative refinement as-is. Note the speculative origin in the done report so the operator knows the spec wasn't topped up just-in-time.

If `refined_speculative` is absent or `false`: skip this step — the refinement is already JIT-fresh.

### Step 1.5 — Context7 freshness pass

Before writing any production code, fetch current docs via Context7 for each library / framework / SDK / API the implementation will touch (Angular, Spring Boot, Tailwind, NgRx Signals, @angular-eslint, Flyway, Testcontainers, Playwright, Keycloak, JPA/Hibernate, springdoc-openapi, etc. — derive from `adr_refs` + design notes + the legacy code being replaced).

Workflow per library: `mcp__context7__resolve-library-id` → pick best match (prefer version-pinned IDs when the story pins a version) → `mcp__context7__query-docs` for the specific question (current API names, deprecations, install commands, peer-dep matrix).

This applies even when you "already know" the API — the modern Angular signal APIs, NgRx Signal Store features, Spring Boot 3.x security DSL, and Tailwind v4 vs v3 conventions in particular have shifted across recent releases and training data may lag. **Library facts from Context7 override training-data assumptions** when they conflict — flag the conflict in the done report so the operator can update the design notes if needed.

Do not fetch docs for general programming concepts, refactoring patterns, or business logic — Context7 is for library / framework specifics only.

### Step 2 — Flip status to `in_progress` + create GitHub issue

Update the story's frontmatter:

```yaml
status: in_progress
started_at: <ISO date>
```

This is so a concurrent operator / agent can see the story is taken. Re-saving the file at the start (not at the end) means crashes don't leak a permanent `todo` lie.

Then, **create the GitHub issue** per the GitHub issue lifecycle section above:

1. If `gh auth status` is OK and a GitHub remote exists, and the story frontmatter has no `github_issue:` field yet, run `gh issue create` with the title / body / labels per the policy.
2. Capture the issue number; stamp it on the story frontmatter as `github_issue: N`.
3. If the frontmatter already has `github_issue: N`, verify the issue is still open. State mismatch → escalate per Step 5.
4. If `gh` is unavailable or the create call fails, skip silently and use the story-ID fallback for commit messages.

Then, **create the feature branch** per the Feature branch + draft PR lifecycle section above:

1. Bail if `main` is dirty — uncommitted state would leak across the branch boundary.
2. `git checkout -b story/S-NNN-<slug>` (or `git checkout story/S-NNN-<slug>` if it already exists from a prior partial run).
3. The draft PR is **not** opened yet — that happens at the first push (after the backend slice is locally green). Opening a draft PR before any code exists is noise.

**Commit this initial state** (status + issue stamp) with message `#N: start` (or `S-NNN: start` in fallback mode) **on the feature branch**. This is the only commit allowed before Step 2.5's red tests — it captures the "work started" milestone for the audit trail.

### Step 2.5 — Define the parity-verification strategy FIRST, then write the tests

Before any production code is written, decide how you will *prove* the new implementation matches the legacy behavior, and write the tests so they fail. This step is mandatory for every story — even greenfield ones (where parity is N/A) benefit from naming the test that will prove each acceptance criterion before the code exists.

#### Pick the parity layer

The story's frontmatter `parity_test` is the operator's guidance; the refinement's `## Test plan` section enumerates the test cases. Your job here is to confirm the *layer* and write the tests at it. Decision tree:

1. **Story is parity-sensitive** (frontmatter `parity_test` is non-empty, OR the story touches a seed sacred cow, OR the story sits in a parity-flagged epic per `_ORDER.md`):
   - **Preferred — e2e tests ported from the legacy Playwright suite or freshly authored against the legacy stack.** Run the same e2e against the new stack; assert zero behavioral delta. Strongest oracle: full-stack, user-observable, framework-agnostic.
   - **Acceptable when e2e is impractical** (selectors require a full rewrite anyway because legacy is AngularJS and new is Angular 21; or the flow is multi-system and e2e setup is genuinely heavy): **API-level integration tests** that exercise the same flow at HTTP layer. Test data captured from legacy via metadata-only extraction (per S-010 patterns) feeds these.
   - **Last resort — unit tests** on the ported logic with fixtures captured from legacy. Use only when the logic is pure (no DB, no HTTP) and the e2e/API path is genuinely infeasible. Document in the done report *why* the higher layer wasn't used.

2. **Story is greenfield** (no legacy equivalent — e.g. the new whitelabel feature, PWA offline-write infrastructure, push notification subscription endpoints):
   - Tests assert the design notes' acceptance criteria directly. No parity oracle exists. Still TDD: tests first.

3. **Story is partially-parity** (refactor that should preserve behavior but also adds new capability — e.g. consolidating two legacy endpoints into one):
   - Parity tests cover the preserved-behavior set; new tests cover the new capability set. Both written first.

#### Don't carry over legacy API design decisions the refinement deliberately restructured

The parity tests assert **observable behavior**, not URL shape, HTTP verb, response envelope, or DTO field naming. The refinement's design notes are the source of truth for the new API surface; parity tests live one layer above that — they ask "does the user / external integration / business rule still get the same outcome?", not "is the new code shaped like the old code?".

**Worked examples — DO NOT lock parity tests onto these legacy shapes:**

- **Dedicated "overview" vs. "detail" endpoints.** Legacy: `GET /api/v1/flights/listitems/...` returns `FlightOverview` (lightweight) and `GET /api/v1/flights/{id}` returns `FlightDetails` (heavy). The refinement may keep two-tier DTOs (ADR 0005 recommends it) but the URL shape is a *design* choice — parity tests assert "the flight-list page renders 50 flights for club X with these columns" + "the flight-detail page renders the full crew list," not "GET /api/v1/flights/listitems is reachable."
- **POST for paginated list.** Legacy: `POST /api/v1/flights/gliderflights/page/:start/:size` with `{filter, sorting}` body. The refinement may pick `POST /api/v1/flights/search` (S-062a's call) or `GET /api/v1/flights?...` with URL-encoded filters. Parity tests assert "filter by immatriculation X returns the expected result set" — not the verb or URL.
- **Per-status separate endpoints.** Legacy may have `/flights/locked`, `/flights/validated`, `/flights/booked` — the new system may consolidate to `/flights?status=...`. Parity tests assert "querying for Locked flights returns the right set," not "GET /flights/locked is reachable."
- **Auth endpoint shape.** Legacy: `POST /Token` with `grant_type=password`. The new system uses OIDC via Keycloak (ADR 0007) — a completely different shape. Parity tests assert "a logged-in user with role X can access protected resource Y," not "POST /Token returns access_token."
- **Implicit client-supplied tenant scoping.** Legacy may accept `?clubId=` in queries. The new system uses structural `@TenantId` (ADR 0008) — tenant resolves from the bearer, not the URL. Parity tests assert "user from club A cannot see club B's flights," not "passing ?clubId=B is filtered."
- **Hand-rolled pagination envelope.** Legacy may return `{items: [...], total, page, size}`. The new system may use Spring's `Page<>` envelope (`{content, totalElements, number, size}`). Parity tests assert "page size 50 returns 50 items and totalElements is accurate," not the envelope's field names.
- **Empty-Guid normalization quirks.** Legacy emits `'00000000-0000-0000-0000-000000000000'` for "no value" (`FlightsController.js:319-324`); the new system rejects empty UUIDs at the wire (S-062a refinement). Parity tests assert "a flight without a co-pilot is created successfully" — not "the response includes an empty Guid."

**The acid-test question:** *would this parity assertion still pass if the new system used a completely different API shape that delivered the same end-user behavior?* If yes — good test. If no — you're testing the legacy implementation, not the legacy behavior; rewrite at a higher layer.

**Exception — preserve the shape when the refinement explicitly said so.** Some legacy shapes are parity-relevant because external systems depend on them: the Proffix API surface (per S-080 / external Proffix sync), the OGN ingestion contract (per S-066 / S-114), the per-tenant `myClub` JSON shape consumed by both auth and public flows. When the refinement's design notes explicitly preserve a legacy API shape, that shape is fair game for parity assertions; otherwise it's not.

#### Write the tests FIRST, fail loudly

Tests at the chosen layer become the first coding act after Step 2 (status flip). They MUST fail because the implementation doesn't exist yet. Concretely:

- **Backend integration tests:** `@SpringBootTest` + Testcontainers Postgres + `MockMvc` / `TestRestTemplate` exercising the endpoints the story adds. Expected outcome: red because controllers don't exist yet.
- **E2E tests:** Playwright specs against `next/web/`'s `ng serve` proxied to `next/server/`'s `bootRun`. Expected outcome: red because routes / components / endpoints don't exist yet.
- **Unit tests:** JUnit 5 / Vitest as appropriate. Expected outcome: red because the unit doesn't exist yet.

**Watch the tests fail before you write code.** A test that passes before implementation is a useless test — it's asserting something already true or asserting nothing. If your initial test pass is green, the test is wrong; rewrite before any production code.

The output of Step 2.5 is a committed (working-tree-only, not git-committed) test scaffold with the parity layer named, every acceptance criterion mapped to a test, and every test failing for the right reason ("expected 200, got 404 Not Found" — not "expected X, got NullPointerException at line 7").

### Step 3 — Build a working plan

Use TaskCreate to track sub-steps. Default ordering:

1. **Parity / acceptance tests already written and red per Step 2.5.** Confirm they fail for the right reasons before continuing. **Commit the red tests** per the commit-and-push policy (do NOT push — CI would just confirm red). If you skipped Step 2.5, go back — TDD ordering is mandatory.
2. **DB migration** (if Domain model changed) — write the new `V*__name.sql` migration first; verify Flyway picks it up; assert the schema is what the design specified. Commit alone or batch with the backend slice per the policy.
3. **Backend code** — entity → repository → service → controller, in that order. Honor the Security plan's `@PreAuthorize` annotations and validation rules; honor the Performance plan's indexes (already in the migration) and fetch strategies. **Commit + push when the backend slice is locally green**; backgrounds the CI watch; continues to step 4.
4. **Frontend code** — Signal Store → component → route, in that order, all consuming the generated TS client. **Commit + push when the frontend slice is locally green**; backgrounds the CI watch; continues to step 5.
5. **Make the remaining tests pass.** Iterate the unit + integration + parity tests until green. Don't skip a failing test; if it's wrong, fix the test before fixing the code. Each green-turning iteration that required real code change is its own commit per the policy.
6. **Run the full local suite.** Don't break previously-done stories. If a previously-green test now fails, stop — that's a regression worth surfacing before continuing. (Don't push a regression onto CI; fix locally first.)
7. **Verify acceptance criteria one by one.** Each criterion in `acceptance:` must map to a passing test (or a manual check note in your report).
8. **Parity test if any.** Run the `parity_test` file or invoke the parity-verification harness; assert zero-delta (or known-delta per the cutover gate).
9. **Drain backgrounded CI watches.** Before declaring done, ensure every push's `gh run watch` has returned. If any are still running, wait for them; if any returned non-zero and weren't addressed, address per the CI-failure-handling section.

#### Parallel sub-tasks within a single story

Where the design notes specify orthogonal sub-tasks across disjoint paths — backend in `next/server/`, frontend in `next/web/`, e2e in `e2e/tests/new/` — you MAY spawn parallel general-purpose Agent calls, one per sub-task, to compress wall-clock. Send them in a single message with multiple tool uses so they run concurrently.

Rules:

- **Disjoint paths only.** Two sub-agents touching the same file → merge pain or silent overwrite. If a story adds an orval-generated TS client that backend regenerates and frontend imports, frontend waits for backend.
- **Same working tree.** Worktree isolation is for multi-*story* parallelism (an orchestrator-level pattern outside this skill); within one story, all sub-agents share the working tree.
- **TaskCreate is the coordination ledger.** Mark each sub-task `in_progress` before dispatch and `completed` when the sub-agent returns. The parent (this skill) holds the ledger; the sub-agents only report their results.
- **Reconcile sequentially in the parent.** Once all sub-agents return, the parent inspects each diff, resolves any cross-cutting concerns (e.g. a backend DTO rename that needs a frontend reference update), then proceeds to Step 4 making-the-tests-pass.
- **Default off for S-estimated stories.** Parallelization overhead doesn't pay off for a single-file change. Reserve for M / L stories with clear backend / frontend / e2e splits.
- **When in doubt, sequential.** A confused merge of three sub-agent outputs is worse than a slightly slower sequential pass.

### Step 4 — Honor the refinement sections, don't override them

The five refinement sections are contracts:
- **Design notes:** the module layout / API shape is decided. Don't redesign mid-implementation. If the design is wrong, stop and escalate; don't silently improvise.
- **Security plan:** every `@PreAuthorize`, every validation rule, every audit event is specified. Implement exactly. If you discover a gap, flag it; don't paper over.
- **Test plan:** every test case is specified. Write each one. If a case turns out to be impossible to test at the specified layer, surface it in the report and write the closest-equivalent at the next layer up.
- **Performance plan:** every required index is in the migration. Every N+1 risk is mitigated (fetch join, `@EntityGraph`, batch size). Latency budget is the post-implement verification target.
- **Open design questions:** if populated, stop and ask the user before continuing. The refine phase explicitly flagged these as un-resolved.

### Step 4.5 — Consult a specialist before escalating

The refinement sections (Design notes, Security plan, Test plan, Performance plan) are contracts — but real code surfaces forks the refinement didn't enumerate. **Before stopping and asking the operator (Step 5), consult one of these read-only specialist agents.** Each takes a tight prompt — one question, with file:line context — and returns a recommendation. If the recommendation is sufficient, proceed; if not, escalate per Step 5.

Available specialists:

| Specialist | Use when |
|---|---|
| `solution-architect` | The fork is "where does this new piece go?" (package layout, class shape, integration with another story's contract). Re-derives if needed; expect a fuller answer. |
| `implementation-architect` | The fork is "the refinement's design has a gap or doesn't fit the code that landed; patch the design without re-deriving." Lighter-weight than `solution-architect`; expects the existing design as a baseline. |
| `security-engineer` | The fork is "is this query / endpoint / persisted field safe to expose to a tenant-scoped flow?" The security plan covered the threat model; this catches the corners. |
| `qa-engineer` | The fork is "is this test the right shape / layer?" or "the parity oracle is ambiguous on case X — preserve or diverge?" The test plan covered the pyramid; this disambiguates a specific case. |
| `performance-engineer` | The fork is "this query plan shows a sequential scan; is the perf plan's index sufficient?" The perf plan covered hot paths; this catches the mis-prediction. |
| `legacy-investigator` | The fork is "the legacy code at file:line does X; is that intentional, a bug, or dead code?" Use when porting parity-sensitive logic and encountering legacy behavior the refinement didn't fully capture. |
| `requirements-engineer` | The fork is "is acceptance criterion N actually meetable as written, given what the code does?" Lighter touch than escalating; the requirements-engineer reads the AC + the code and either confirms the criterion holds or flags a concrete acceptance-criterion bug for operator decision. |

**Rules:**

- **Read-only.** Specialists never write code; they advise.
- **Tight prompt.** One question, with file:line refs + the design-notes section the question hangs off. Don't ask "what should I do?" — ask "fork X: should it shape A or shape B?"
- **One consult per fork.** Don't chain consults (specialist → another specialist → user). If the first consult doesn't resolve the fork, escalate to the user per Step 5.
- **Record the consult in the done report.** Which specialist, what fork, what recommendation. The operator may want to update the refinement template for similar future stories.
- **Don't consult to delay decisions you should own.** "Is this variable name OK?" is not a fork. Forks are decisions the design notes didn't make; cosmetic / mechanical choices are yours to make and move on.

### Step 5 — Escalation triggers (stop and ask)

Stop and surface to the user — do not improvise — if:

- A parity test fails and the only way to make it pass is to change behavior (not a bug fix). The story's parity oracle is wrong, or the new system has a real divergence.
- A previously-green test in another story fails because of this story's changes. Regression — stop, don't bundle.
- A `depends_on` story's artifact is missing despite the story being `done`. Something rotted.
- The legacy code you're porting has an apparent bug. Don't silently fix it; ask whether to preserve.
- An acceptance criterion is unmeetable as written, given what the code actually does or what the platform allows.

For each, ask the user with a precise, single question. Don't pile up alternatives.

### Step 6 — Final verification

- Every acceptance criterion green (point to the test name or manual-check note).
- Test suite green (full server `./gradlew test`, full web `pnpm test`, full Playwright `playwright test` if applicable).
- Parity test green or zero-delta verified.
- Performance plan's latency target met (where measurable in test; defer prod measurement to S-111).
- No regression in previously-done stories.

### Step 6.5 — Reflect conventions in docs (only when something durable changed)

If the story established a pattern others will follow, or surfaced a decision an ADR doesn't yet capture, update the relevant doc. The bar is: *another implementer asks "how do we do X?" tomorrow — is the answer in code + ADRs + conventions?* If not, write the missing line.

**Update — when:**
- **A new pattern others will mirror** (tenant-scoped read, Signal Store composition, Testcontainers slice setup, validation helper, error-translation shape): add a 2-5 line entry to `next/server/CONVENTIONS.md` or `next/web/CONVENTIONS.md` (create if missing). Cite the canonical example by `file:line` — don't paraphrase the code.
- **An ADR's intent shifted** (implementation revealed a corner the ADR didn't cover, or a criterion changed weight): **propose** an ADR amendment in the done report. Don't auto-edit ADRs.
- **A workflow / operational decision** (new env var, deployment knob, CI gate, secret name): one-liner in `docs/modernization/operations.md` (create if missing).

**Do NOT update:**
- Anything restating what the code says (controller endpoints, DTO shapes, function signatures — those live in the OpenAPI spec and the source).
- Per-story changelogs — the git log + story file already capture that.
- `flsserver/` or `flsweb/` READMEs — legacy is reference-only.

Most S-sized stories: no doc update. Note `(no doc changes)` in the done report and move on.

If doc *was* updated, **commit it as its own work-package** with message `#N: docs — <one-line subject>` (or `S-NNN: docs — …` in fallback) before Step 7's status flip.

### Step 6.7 — Self-review against the contract (pre-push gate)

Before flipping `status: done` and pushing the final commit, run a single read-only consult against the diff to catch the obvious contract violations that `/modernize-review` would otherwise flag as blockers. The goal isn't a full review (that's phase 7) — it's a cheap last-mile pass that closes the most common review→rework loops at source.

**Why this exists:** the implement phase optimizes for getting tests green; a tired implementer ships diffs that pass tests but skip an `@PreAuthorize`, miss an acceptance criterion's test mapping, or silently re-shape the API away from the design notes. Catching those before the PR flips ready-for-review shrinks the average review→rework loop from ~1.5 cycles to ~1.0.

**How to run:**

1. Spawn **one** `maintainability-reviewer` Agent call with a scoped prompt:
   - Story file path.
   - ADR paths from `adr_refs`.
   - The diff range — `git diff main...HEAD` from the story branch (or the equivalent commit range in fallback mode).
   - **Scope override (this is the critical bit — different from the phase-7 invocation):** "**Blockers only.** Surface only findings that break a refinement contract, ADR, sacred cow, or security invariant, or that leave an acceptance criterion without a passing test. Skip improvements and nudges entirely — those belong to phase-7 review. If no blockers exist, return `(none)`."
   - The five refinement-section paths to anchor the contract baseline.
2. Wait for the consult to return (single agent, no fan-out, fast — typically under 90 seconds).

**Disposition:**

- **No blockers (`(none)`):** proceed to Step 7. Record the consult in the done report.
- **Blockers found, all addressable in a single small commit:** fix them now, commit as `#N: self-review fixes — <one-line summary>` (or `S-NNN: self-review fixes — …` in fallback mode), push, watch CI, then proceed to Step 7. The done report names the consult, lists the blockers, and shows the fix commit.
- **Blockers found that require a real redesign (design-notes contradiction, missing acceptance criterion, ADR conflict):** **stop and escalate per Step 5.** Don't fix-and-push past a structural blocker — that's exactly the case that should not silently disappear into a self-review fix commit. Surface to the operator with the consult's finding verbatim.

**Rules:**

- **One consult per story.** Re-running self-review after a fix commit is allowed once (to confirm the fix landed); a third invocation is a sign the consult is hunting and should be escalated.
- **The consult is read-only.** It never writes code. Fix commits are the parent skill's job.
- **Do not downgrade.** A finding the consult flagged as a blocker is not "actually fine on second thought" — either fix it or escalate it. The temptation to wave findings through is exactly what this gate exists to resist.
- **Skip the gate only if the diff is bookkeeping-only** — e.g. a story that only flips frontmatter or only touches docs. Note the skip + reason in the done report.
- **Specialist consult logged in done report.** Step 7's report lists this consult alongside any other Step 4.5 consults — operator can see the full advisory trail.

### Step 7 — Update status, final commit + push, close issue, report

Update the story's frontmatter:

```yaml
status: done
done_at: <ISO date>
```

**Commit the status update** with message `#N: mark done` and body `Closes #N` (in fallback mode without GitHub: `S-NNN: mark done`, no `Closes` keyword). **Push.** Watch the final CI run; if it goes green, proceed; if it goes red, fall into the CI-failure-handling section before reporting done.

**Mark the PR ready-for-review** (if GitHub is configured):

1. `gh pr ready <PR>` — flips draft → ready-for-review. This is the signal that the story is now eligible for `/modernize-review`.
2. Apply the `status/done` label on the tracking issue if it exists: `gh issue edit N --add-label status/done --remove-label status/in-progress`. The issue stays **open** until the operator merges the PR — `Closes #N` only fires on merge.
3. Post the final summary comment on the issue (parity layer used, commit count, CI run outcomes, PR URL).

In fallback mode (no GitHub / no PR): close the issue manually with `gh issue close N` if `gh` is available; otherwise skip.

Print to the user:

- Story ID + title.
- **Branch:** `story/S-NNN-<slug>`. In fallback mode: `(trunk — no feature branch; reason: <gh unavailable / no remote / pr-create failed>)`.
- **GitHub issue:** `#N` + URL + state (typically still `OPEN` — closes on PR merge). In fallback mode: `(no GitHub issue — local commits only; reason: <…>)`.
- **GitHub PR:** `#M` + URL + state (`READY_FOR_REVIEW`). Operator action: review (`/modernize-review S-NNN`) then merge. In fallback mode: `(no PR — commits pushed directly to current branch)`.
- **Parity strategy used:** which layer (e2e / API integration / unit), with one-sentence rationale. If you dropped below e2e, *why* the higher layer was infeasible. If the story is greenfield (no parity oracle), state that.
- **Commits made:** count + the title of each (one line each, prefixed with `#N` or `S-NNN`). Reader scans this in a few seconds to see the story's logical decomposition.
- **CI runs:** count + outcomes (`run-id: status`). If any required a fix-commit, name it.
- Files changed (count + summary by area: backend / frontend / db / tests).
- Tests added (count by layer).
- Acceptance criteria status: each criterion + how it was verified.
- Parity test result (if applicable): pass / known-delta-with-rationale.
- Performance test result (if applicable): measured latency vs. budget.
- **Self-review consult (Step 6.7):** outcome (`no blockers` / `<N> blockers fixed in <commit>` / `escalated`), and any blocker text the consult surfaced. If skipped (bookkeeping-only diff), the skip reason.
- **Specialist consults made (if any):** which agent, what fork, what recommendation, whether the recommendation was followed. The operator may want to fold recurring forks back into the refinement template.
- **Parallel sub-agents used (if any):** what split (e.g. backend / frontend / e2e), wall-clock saved (approx).
- **CI / push fallback used (if any):** when CI wasn't configured / `gh` not installed / no remote, document that commits are local-only and the operator picks up.
- **Doc updates:** which conventions / ops docs were touched (with commit ref), or `(no doc changes)` if the story didn't establish a durable pattern.
- **ADR amendments proposed (if any):** which ADR + what shifted + recommended change. Mark explicitly as *proposed* — the operator decides whether to amend. (Surfaced again at `/modernize-finalize` time.)
- Suggested next action: `/modernize-review S-NNN` — review the story you just implemented. Then `/modernize-rework` (if findings) → `/modernize-finalize` (to merge). Refining the *next* story (`/modernize-refine <next-S-id>`) waits until this one is merged.

## Quality bar

- **One story per invocation.** Never bundle stories. Even tiny S-estimated stories run independently.
- **Refinement must exist.** `refined: false` is a hard bail.
- **Context7 freshness pass before code.** Step 1.5 is not optional when the story touches any library / framework / SDK / API. Library facts from Context7 override training-data assumptions when they conflict; flag conflicts in the done report.
- **Parity strategy defined FIRST, tests written FIRST.** Step 2.5 is mandatory before any production code. Watch the tests fail for the right reason before writing the code that makes them pass. A test that passes before its implementation existed is a broken test.
- **Parity tests assert behavior, NOT legacy API shape.** Pass the acid-test question: would this parity assertion still pass if the new system used a completely different API shape that delivered the same end-user behavior? If no, rewrite the test at a higher layer or against the right invariant. Exception: shapes the refinement explicitly preserves (Proffix API, OGN contract, etc.) are parity-relevant.
- **Pick the highest-feasible parity layer.** e2e > API integration > unit. Drop down a layer only when the higher one is genuinely infeasible, and document why in the done report.
- **Honor the refinement contracts.** The five sections are not suggestions.
- **Commit per work-package, not per file.** `#N: <summary>` prefix when GitHub is configured (story-ID `S-NNN: <summary>` fallback when not); no Conventional Commits prefix. ≤ 10 commits per story.
- **One GitHub issue per story.** Created at Step 2, frontmatter-stamped, closed by `Closes #N` in the final commit. Per-commit comments are forbidden; milestone comments only (parity strategy, CI failures, done summary).
- **Reference the issue, not the MD file.** Commits link via `#N`; the issue body links to the MD file. The MD file is the canonical design doc; the issue is the status tracker. Don't pollute the commit log with story-MD paths.
- **Push at locally-green work-package boundaries only.** Don't push red tests; don't push regressions. Backgrounded CI watch is the verification.
- **Don't push past a red CI build.** Stop the foreground work, comment on the issue, fix in a new commit, re-watch, then resume.
- **Don't break green.** A regression in another story's tests (locally or on CI) is a stop condition.
- **Acceptance criteria → tests.** Each criterion has a named test (or a documented manual-check).
- **Update status atomically.** `in_progress` at the start, `done` at the end. No half-states.
- **Parallel sub-agents on disjoint paths only.** Same-file conflicts between sub-agents are silent failures.
- **Specialist consults are read-only and one-shot.** No code-writing specialists; no chained consults.
- **Self-review gate before push.** Step 6.7's `maintainability-reviewer` consult runs blockers-only against the diff before the status-flip push. Either zero blockers (proceed), small fix commit (proceed), or escalate (stop). Skip only for bookkeeping-only diffs; note the skip in the report.
- **One feature branch per story.** `story/S-NNN-<slug>`, branched off `main`. Draft PR opens at first push, flips to ready-for-review at status:done. Trunk-direct commits only in the GitHub-unavailable fallback.
- **The skill does not merge.** The PR sits ready-for-review for `/modernize-review` and then the operator. Auto-merge is not a feature of this skill.
- **Code is self-explanatory.** Default to zero comments. Lean on naming, modularization, and structure to communicate intent. Add a comment only when the WHY is non-obvious — a hidden invariant, a workaround for a specific bug, a surprising constraint. Never write comments that restate the WHAT (the code does that), reference the current task / issue ("added for #42"), or paraphrase function names in docstrings. If you feel the urge to comment, first try renaming a variable, extracting a function, or splitting a module.
- **Docs capture decisions and conventions, not code.** If the story established a pattern others will mirror, add a short entry to the relevant `CONVENTIONS.md` citing the canonical example by `file:line`. If an ADR's intent shifted, propose an amendment in the done report (don't auto-edit). Otherwise no doc changes — most stories don't need any.

## What this skill does *not* do

- It does not write or modify ADRs. ADR amendments are *proposed* in the done report; the operator decides.
- It does not document what the code already says. No README sections paraphrasing controller endpoints (the OpenAPI spec does that); no comments restating obvious behavior.
- It does not split stories or rewrite acceptance criteria. If the story is wrong, stop and escalate.
- It does not force-push, amend pushed commits, or rewrite history. Mistakes are fixed forward with new commits.
- It does not push to `main` directly — story-per-branch is the default workflow. Trunk-direct only in the documented fallback when `gh` is unavailable or `gh pr create` fails.
- It does not merge PRs. The PR is left ready-for-review; `/modernize-review` runs against it and the operator merges.
- It does not delete GitHub issues. Mistakes are fixed by reopen + clarifying comment, never `gh issue delete`.
- It does not transfer issues between repos or move them to GitHub Projects boards. Label-level tracking is the limit; richer project-management is the operator's call.
- It does not auto-create missing labels. If the labels named in the GitHub-issue-lifecycle policy don't exist in the repo, the issue is created without them — the operator owns the label taxonomy.
- It does not run the production deploy. That's S-121.
- It does not refine — that's `/modernize-refine`. If a story arrives with `refined: false`, bail.
- It does not update `_ORDER.md`. The order is fixed at decompose time.

## When done

The story is `status: done`, the tests are green, the user has a clear next action (the next story in `_ORDER.md`), and the working tree has the diff staged for the operator to review.

If the user wants to keep going, they invoke `/modernize-refine <next>` followed by `/modernize-implement <next>`. No batch mode.
