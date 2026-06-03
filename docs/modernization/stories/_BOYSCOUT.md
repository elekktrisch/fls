# Boyscout riders

Fix-forward backlog. Per the operator's standing rule (**no tiny stories** — see
[[feedback_no_tiny_stories_fix_forward]]), mechanical/bounded work (bug fixes,
one-liners, doc reconciliations, guard tests, file deletions — however many) does NOT
get its own story/journey. It's recorded here and **folded into the next journey** that
runs the gate, so the fix flows through the do-* workflow and produces gate + gallery
proof the operator can see.

`/do-plan` (Mode B) scans this file for riders touching the journey's surface and notes
them in the journey file; `/do-ship` folds them into the task list (sized per its gate)
and **clears the bullet here as it ships**. A standalone journey is filed only for
genuinely new vertical feature scope.

## Pending (filed by /do-retro 2026-06-02, J-0b+J-0c window)

- **modernize-* sunset.** do-* is proven on J-0/J-0b/J-0c (the 2-3 bar is met). Delete the 9
  `modernize-*` skills + ~12 modernize agents and prune the `rolled_up_into:` horizontal
  stories. Mechanical (however many files) → rides forward; ideally after do-* ships one
  *non-migration* journey (early proofs are all fan-out flavored). 47 `implemented/` stories
  stay as history. *(seam: .claude/skills/modernize-*, .claude/agents/*, rolled_up_into stories)*

## Pending (filed by /do-retro 2026-06-03, J-1 window)

_(no pending riders — see Shipped below)_

_Deferred (operator, Q3): fanout-perf (own runner / sharding / no spec co-location) + re-enable
the T-18 J-0c rename test — recorded in the J-1 journey file, not filed as an active rider yet._

_Scan note: no e2e specs carry `@helper`/`covered-by` tags yet → no helper-pruning rider this round._

## Shipped

- **ci.yml path-filter for docs/story-only pushes** — shipped J-2 T-11. Root cause: on
  `integration/**` branches the `pull_request` trigger made `dorny/paths-filter` diff the
  WHOLE PR vs `main`, so `changes.next` was always true (the branch already carries
  alpenflight/ commits) → every doc-only push re-ran build+proof. Fix: a new
  `changes.docs_only` output computed from the INCREMENTAL push diff
  (`github.event.before..after`); the three heavy jobs now gate on
  `next == 'true' && docs_only != 'true'`. Fail-safe toward running (undeterminable range
  → run). `required` aggregator stays green via the existing skipped-to-success case.
