---
id: S-092
title: Decommission Workflow.Activator + Alpinely.TownCrier references
epic: E-10
status: todo
depends_on: [S-083, S-084, S-085, S-086, S-087, S-088, S-089, S-090]
acceptance:
  - All jobs ported to Spring `@Scheduled`; cron-on-host references to `FLS.Workflow.Activator` are removed from the new deployment recipe.
  - `Alpinely.TownCrier` is not referenced in any new-stack file; all email templates are Thymeleaf.
  - `Ionic.Zip` is not referenced; all zipping uses `java.util.zip.ZipOutputStream`.
  - Legacy files remain in place on the `flsserver/` side (we don't modify legacy); this story is about the *new* stack having no references.
estimate: S
adr_refs: [0009, 0012, 0013]
parity_test: none
---

## Context
Modernization scope notes from ADRs 0009, 0012, 0013. Verification story.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Grep new-stack code for the legacy names.
- [ ] Remove any lingering references.
- [ ] Document in `alpenflight/server/README.md` that these legacy components are decommissioned in the new stack.

## Notes
The legacy app itself still uses these — they exit the picture when each operator turns off their own legacy FLS deployment. This story is about the new code not accidentally re-introducing them.
