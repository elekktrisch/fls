---
name: feedback-parallel-agents-need-single-message
description: "To actually parallelize Agent (or any tool) calls, all calls must be in ONE assistant message — separate messages serialize."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 60c6c053-e3a6-4f91-ac7c-5232fd92d23a
---

When the skill or task says "run N specialists in parallel," that means **one assistant message with N tool-call blocks**. Splitting them across consecutive messages — even back-to-back — runs them sequentially.

**Why:** The harness fires tool calls within a single message concurrently; messages themselves are serial. The `/modernize-refine` skill (and similar workflows) explicitly relies on this for runtime cost — 5 serialized specialists ≈ 5× the wall time of 5 parallel ones, and the operator notices.

**How to apply:** before sending the message, look at the planned tool calls. If they're independent (no later one depends on output from an earlier one), batch them ALL into a single response. Resist the impulse to send one, watch the result, then send the next — that pattern is for *dependent* calls.

The specific trap this came from: `/modernize-refine S-019` re-refine. Sent requirements-engineer in msg 1, solution-architect in msg 2, then qa+security+perf each in msgs 3/4/5 — claimed "in parallel" in the prose but the wall clock said otherwise.
