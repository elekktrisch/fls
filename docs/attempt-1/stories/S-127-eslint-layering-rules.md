---
id: S-127
title: Enforce atomic-design + cross-feature layering via ESLint
epic: E-01
status: todo
estimate: S
parity_test: none
depends_on: [S-008]
adr_refs: []
refined: false
origin: rework
origin_story: S-002
origin_finding: `alpenflight/web/CLAUDE.md` §1 / §2 promise ESLint enforces atomic-design layering (atoms import nothing from molecules/organisms) and forbids direct cross-feature imports. S-002's `eslint.config.mjs` doesn't carry these rules — the skeleton has no siblings yet so the rule isn't load-bearing, but the convention claim is unenforced.
---

## Context

Follow-up from review of S-002 (originating story). The originating story's review found:

> `eslint.config.mjs` doesn't enforce `no-restricted-imports` for atomic-design layering or cross-feature imports — `CLAUDE.md §1` / §2 promise ESLint enforces "atoms import nothing from molecules/organisms" and "direct imports between feature folders are forbidden." The skeleton has no siblings yet so the rule isn't load-bearing today, but the convention claim is currently unenforced. Reasonable to defer to S-008 / first cross-feature story.
> **Path:** `alpenflight/web/eslint.config.mjs`.

See [`S-002-scaffold-web-skeleton.md`](S-002-scaffold-web-skeleton.md#review) for full review context.

## Acceptance criteria

- [ ] `eslint.config.mjs` adds `no-restricted-imports` rules that enforce the atomic-design layering: atoms can't import from `@ui/molecules/*` or `@ui/organisms/*`; molecules can't import from `@ui/organisms/*`.
- [ ] Cross-feature imports forbidden: `src/app/features/<a>/**` cannot import from `src/app/features/<b>/**` (any b != a). Cross-feature sharing must go through `@shared/ui/*`, `@shared/util/*`, or `@core/*`.
- [ ] A negative test (an intentionally bad import) demonstrates the rule fires.
- [ ] CLAUDE.md §1 / §2 cite the rule by name.

## Notes

Depends on **S-008** (Component primitives kit) — the first story that ships actual atomic-design components and would benefit from the rule biting on real paths. Wiring earlier on empty dirs risks rules that don't match anything.
