---
name: feedback-derive-before-asking
description: "For elicitation-heavy workflows (modernization skills, design discussions, ADR drafting), derive answers from the legacy code first and only ask the user when the answer requires human judgment they alone hold"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9eef1635-3cb0-4e58-9ad4-2450de37de4b
---

When running interactive workflows that produce artifacts about an existing system — modernization skills, design discussions, ADR drafting, decomposition, backlog generation — **read the legacy code first** and use its findings to answer the question. For artifact-generation work (story writing, decomposition), `AskUserQuestion` is a **last resort, not a default**. The user reviews and adjusts artifacts after they exist; do not block on questions during construction.

**Two intensity levels, depending on workflow type:**

1. **Decision-making workflows (vision, ADRs):** Default to writing the artifact with the code-supported pick, then offer a one-line override. Reserve `AskUserQuestion` for genuine forks where human judgment is the only signal: outcome priority / weighting, team skill or language preference, budget/capacity/timeline, regulatory or compliance choice (residency, retention, MFA), cutover risk tolerance, vendor preference between technically-equivalent options.

2. **Artifact-generation workflows (decomposition, story writing):** Go further — write everything autonomously. Pick an answer, record it as an assumption in the artifact, surface the list of assumptions at the end. `AskUserQuestion` count for a typical run should be 0 (or 1 if a precondition like "existing files — refresh/merge/abort?" forces it). The user pushes back on assumptions in review, not during writing.

**Why:** during the FLS phase-2 and phase-3 modernization sessions (2026-05-14), the skills asked the user ~20 questions over the course of vision + ADRs. Many had code-supported answers that the user just confirmed — friction without value. The user flagged this directly: "modernize-skill should prioritize analyzing the legacy code over asking the user" and then escalated for story writing: "I'd even go one step further: story writing should be maximum autonomous. avoid AskUserQuestion unless absolutely necessary."

**How to apply:**
- In the FLS modernization workflow specifically, the skill files at `.claude/skills/modernize-{discover,decompose}/SKILL.md` were updated to encode this. `modernize-discover` now writes a §8 "Findings pre-answered for downstream phases" feed-forward table. `modernize-decompose` now requires reading legacy code per epic, defaults to fully-autonomous artifact generation, and surfaces autonomous decisions as an `## Assumptions made` section in its final summary.
- For any elicitation or generation skill on this project, apply the same rule: derive first, write autonomously, surface assumptions afterwards. Reserve user questions for blockers — never for content that can be derived or for decisions the user can revise after seeing the result.
- See also: [[fls-modernization-workflow]] for the broader workflow shape.
