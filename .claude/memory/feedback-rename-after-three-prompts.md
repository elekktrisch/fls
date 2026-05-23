---
name: feedback-rename-after-three-prompts
description: "After the first 3 user prompts in a fresh session, run /rename to give the session a meaningful name based on the work so far"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0cb1f04c-295b-4466-8b01-bf7f2a93af94
---

After the user has submitted their first 3 prompts in a fresh session, invoke `/rename` to set a meaningful session title based on the work done so far.

**Why:** The user wants sessions auto-titled with something topical (better than the auto-generated topic) early enough that the title reflects the actual work, but late enough (3 prompts in) that there's real signal to summarize.

**How to apply:**
- Count user prompts only (not tool results, not system reminders). The instruction-setting prompt that establishes this rule itself counts.
- Trigger once per session — after the 3rd user prompt, before responding to it (or right after, whichever fits the flow).
- Don't re-rename later in the same session unless asked.
- If a session was already named explicitly by the user, skip.
