## Modernization workflow & PR hygiene
- [Modernization workflow](fls-modernization-workflow.md) — 4-phase spec-kit flow in docs/modernization/ + `.claude/skills/modernize-*`; greenfield rewrite, hard cutover, sibling alpenflight/ folders.
- [Derive before asking](feedback-derive-before-asking.md) — derive from legacy code first; artifact-generation goes autonomous, surfaces decisions as `## Assumptions made`.
- [Always squash-merge](feedback-always-squash-merge.md) — squash every PR on fls without asking; skip the strategy prompt.
- [Boyscout rule (incl. meta-improvements)](feedback-boyscout-rule-over-clean-prs.md) — fix pre-existing bugs in the same PR; trivial cleanups roll into next story via [[pending-boyscout-followups]]; rework Step 3.5 meta-improvements never get their own chore/* branch.
- [Pending boyscout queue](pending-boyscout-followups.md) — list of trivial cleanups waiting for the next story PR.
- [Dirty refines go on story branch](feedback-dirty-refines-go-on-story-branch.md) — never commit a refine to main; stash → branch → pop. Otherwise the refine rides another story's squash invisibly.
- [No SHAs in committed docs](feedback-no-shas-in-committed-docs.md) — broken by construction + erased by squash-merge. Cite by file:line / PR# / story-ID.
- [No per-blocker GH issues](feedback-no-per-blocker-issues.md) — `/modernize-review` Step 6 dropped; story file's `## Review` is canonical.
- [Push policy](feedback-ask-before-pushing.md) — push freely; only ask first when `.github/workflows/e2e.yml` (or other expensive CI) is in the diff.
- [Re-runnable over frozen docs](feedback-re-runnable-over-frozen-docs.md) — extraction/parity tooling prefers re-runnable scripts over big frozen MD or committed JSON.

## Coding & testing
- [Targeted tests, not full suite](feedback-targeted-tests-not-full-suite.md) — during implement use `--tests 'pkg.*'`; full backend suite > 5 min. CI is the safety net.
- [FE tests: unit for logic, Playwright for DOM](feedback-fe-tests-unit-for-logic-playwright-for-dom.md) — alpenflight/web vitest covers logic classes only; Playwright owns rendering / a11y / routing.
- [E2E screenshots for verification](feedback-e2e-screenshots-for-visual-verification.md) — Playwright specs write `screenshots/<feature>/<state>.png` so Claude can read the UI without operator intervention.
- [Tailwind only, no component CSS](feedback-no-component-css-tailwind-only.md) — alpenflight/web: zero `styles:[...]` arrays; templates use utilities; styles.css holds only tokens + ng-zorro overrides.
- [Component kit: ng-zorro + Tailwind](project-component-kit-ng-zorro.md) — ng-zorro-antd primitives + Tailwind v4 tokens (S-008). From-to datepicker + autocomplete dropdown decided it.
- [IdP-portable validators, DB for identity](feedback-idp-portability-no-keycloak-specific-validators.md) — prod IdP may be Google/Ory/Auth0; user/tenant mapping via DB, not vendor-specific claim shapes.
- [Login UI in Keycloak, not SPA](project-login-in-keycloak.md) — landing has a Sign-in CTA; the form is Keycloak's hosted UI. "Polish the login" = Keycloak theme work.

## Operational gotchas
- [FLS server IPv6 binding](fls-server-ipv6-binding.md) — legacy Mono on `localhost:25567` is IPv6-only; webpack proxy 502s unless `FLS_LISTEN_URL="http://*:25567/"`.
- [FLS e2e setup](fls-e2e-setup.md) — Playwright stack booby-traps (Node split, yarn-start trap, seed cache, output dirs, spinner wait).
- [FLS e2e state](fls-e2e-state.md) — in-repo e2e docs + summary of self-contained-parallel model + remaining failure islands.
- [Use dev-up-full.sh, not compose](feedback-use-dev-up-full-not-compose.md) — local dev bring-up via `next/ops/dev-up-full.sh` (legacy=`fls-e2e`, new=`alpenflight-dev`), never ad-hoc compose project names.
- [Angular Vite proxy needs `**`](feedback-angular-vite-proxy-glob.md) — `proxy.conf.json` paths use `/api/v1/**`; single `*` doesn't cross `/`.
- [Parallel agents need single message](feedback-parallel-agents-need-single-message.md) — to parallelize Agent calls, batch them in ONE assistant message; separate messages serialize.
- [Rename session after 3 prompts](feedback-rename-after-three-prompts.md) — once per session, after the 3rd user prompt, run /rename for a meaningful title.

## Project state & decisions
- [Rebrand to AlpenFlight](project-rebrand-alpenflight.md) — FLS → AlpenFlight (alpenflight.ch); shipped via S-128 on 2026-05-16. Domain reg + TM filing deferred.
- [Walking skeleton: Clubs CRUD + mocked auth](project-walking-skeleton-clubs-mocked-auth.md) — S-048 lands early as kit showcase with mock auth; rip-out when S-019/S-020/S-022/S-026 land.
- [Demo-mode feature](project-demo-mode-feature-note.md) — prospective users get try-it/demo mode; tracked in vision §8.
- [Legacy bulk import](project-legacy-bulk-import.md) — cutover imports N clubs × M users at once; S-028 is the building block.
- [clubId resolution not only JWT](project-clubid-resolution-not-only-jwt.md) — Google OIDC users have no clubId claim; S-022 reads claim OR falls back to DB lookup by sub/email.
