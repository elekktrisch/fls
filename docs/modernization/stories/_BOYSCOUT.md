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

- **Docker Hub image-pull retry.** The fanout + nightly workflows pull images
  (mailpit/pgAdmin/Keycloak/MSSQL); a transient Docker Hub timeout caused a non-code red
  round. Add bounded pull-retry to the image-pull steps. *(seam: .github/workflows/
  alpenflight-proof-fanout.yml + nightly.yml image-pull steps)*
- **modernize-* sunset.** do-* is proven on J-0/J-0b/J-0c (the 2-3 bar is met). Delete the 9
  `modernize-*` skills + ~12 modernize agents and prune the `rolled_up_into:` horizontal
  stories. Mechanical (however many files) → rides forward; ideally after do-* ships one
  *non-migration* journey (early proofs are all fan-out flavored). 47 `implemented/` stories
  stay as history. *(seam: .claude/skills/modernize-*, .claude/agents/*, rolled_up_into stories)*

_Scan note: no e2e specs carry `@helper`/`covered-by` tags yet → no helper-pruning rider this round._
