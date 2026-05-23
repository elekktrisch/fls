---
name: feedback-archive-in-mark-done-commit
description: "Story archive (mv to implemented/) rides in the mark-done commit, not a separate finalize step. Rule lives in the modernize-* skills; this memory is a pointer."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5a9ea496-b197-4282-a5ad-4865de72232a
---

The story-archive move (`git mv stories/S-NNN-*.md stories/implemented/S-NNN-*.md`) rides in the same commit as the `status: done` frontmatter flip — folding it in saves one CI cycle vs. the previous flow that archived during finalize. The rule is now self-contained in the skill files; this memory exists only as a pointer in case you're tracing the history.

**Where the rule lives now:**
- `/c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-implement/SKILL.md` Step 8 — mandatory ordering (edit frontmatter at original path → `git mv` → `git add <new-path>` → verify rename detection) and the `git-mv` trap-guard.
- `/c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-finalize/SKILL.md` precondition 2 + Step 2.5 — finalize finds the story already in `implemented/` and just adds `merged: true` / `merged_at` stamps.
- `/c/Users/roman/IdeaProjects/fls/.claude/skills/modernize-review/SKILL.md` + `modernize-rework/SKILL.md` precondition 1 — both run against `stories/` OR `stories/implemented/` because the story may have been archived already.

**Why:** surfaced 2026-05-17 after S-003 implement+finalize burned two CI cycles for the S-123 fold-in archive move that could have been one. Replaced 2026-05-18 — rule moved into skills with self-contained explanation.

**How to apply:** if you find yourself wanting to relocate a story file in a separate commit, you're regressing. The skill files carry the canonical workflow.
