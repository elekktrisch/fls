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

- **ci.yml path-filter for docs/story-only pushes.** Docs/skill/story-only commits
  (`docs/**`, `.claude/**`, root `*.md`) re-trigger the full `alpenflight build` +
  `alpenflight-proof` real-idp (~7 min) — J-1 burned many cycles re-running the heavy proof
  on doc-only pushes. Add a `paths-ignore` / `detect-changes`-gated skip so doc-only pushes
  don't run build+proof, keeping the `required` aggregator green via the standard
  skipped-to-success pattern. *(seam: .github/workflows/ci.yml path filter + required aggregator)*
  — /do-retro Q3 efficiency.

_Deferred (operator, Q3): fanout-perf (own runner / sharding / no spec co-location) + re-enable
the T-18 J-0c rename test — recorded in the J-1 journey file, not filed as an active rider yet._

_Scan note: no e2e specs carry `@helper`/`covered-by` tags yet → no helper-pruning rider this round._
