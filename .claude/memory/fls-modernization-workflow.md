---
name: fls-modernization-workflow
description: "A 6-phase spec-kit-style modernization workflow exists for FLS — what it is, where to find it, what's already decided vs. open. Phases 1-4 are batch planning; phases 5-6 are per-story refinement + implementation driven by 5 specialist subagents."
metadata: 
  node_type: memory
  type: project
  originSessionId: 5a4ec532-dc8e-4fe9-a592-5112895c8cee
---

A spec-kit-style modernization workflow for FLS lives in this repo. Six chained Claude Code skills drive it (split across two stages); artifacts land in `docs/modernization/` and (in phase 6) code lands in `next/`.

**Why:** the user wants a greenfield rewrite of `flsserver` (.NET Framework 4.5) + `flsweb` (AngularJS 1.4) planned via requirements-engineering, not an ad-hoc port. Modeled loosely on GitHub spec-kit (specify → plan → tasks → refine → implement).

**How to apply:** if the user references the modernization plan, ADRs, "the rewrite," epics/stories, refinement, or asks me to continue executing the modernization, start by reading `docs/modernization/README.md`, then `00-seed.md` for strategic anchors, then whichever phase output exists.

**Phase layout (added 2026-05-14):**
- Phases 1–4 (one-shot batch planning): `/modernize-discover` → `/modernize-vision` → `/modernize-adrs` → `/modernize-decompose`. Output: `01-current-state.md`, `02-vision-and-constraints.md`, `adrs/*.md`, `epics/*.md`, `stories/*.md`, `_ORDER.md`.
- Phases 5–6 (per-story execution): `/modernize-refine S-NNN` → `/modernize-implement S-NNN`. Refine spawns 5 specialist subagents in parallel (requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer in `.claude/agents/`), synthesizes their analysis into new sections in the story file, sets `refined: true`. Implement reads the refined story, writes code + tests, sets `status: done`. **One story per invocation — never batch.**

**Status as of 2026-05-14:** Phases 1, 2, 3, 4 complete (`01-current-state.md`, `02-vision-and-constraints.md`, 13 ADRs, 14 epics, **122 stories**, `_ORDER.md`). Phases 5 + 6 skill+agent definitions written but not yet exercised on any story.

**Story lifecycle:** `todo` (post-phase-4) → `refined: true` flag added (post-phase-5) → `in_progress` (start of phase 6) → `done` (end of phase 6).

**Strategic anchors (locked — don't re-litigate):**
- Greenfield rewrite, not strangler-fig or in-place upgrade.
- Old + new run separately; **hard cutover** at the end.
- New code lives under a single `next/` subtree (working slug) with `next/server/`, `next/web/`, `next/database/`, `next/auth/`, `next/ops/`. The `next/` parent is renamed to the final product slug at cutover via story S-120.
- MD-only artifacts for now (no GitHub Issues sync).
- Sacred cows (must survive): multi-tenancy enforced *structurally* (not by convention as today), two-dimensional flight state machine, User/Person split, accounting rules engine parity, OGN inbound contract, Proffix outbound contract. Full list in `00-seed.md`.

**ADRs accepted (2026-05-14):** Java 25 + Spring Boot 4.x; Postgres 17; Flyway; Angular 21 + Tailwind + TS; REST + OpenAPI (springdoc) + generated TS client; NgRx Signal Store; OIDC with Keycloak local + hosted IdP in prod (vendor TBD); Hibernate `@TenantId` discriminator; Spring `@Scheduled` in-process; VPS+Compose day-1 with K8s mid-term; OSS self-hosted Loki/Prometheus/Grafana/Sentry; Apache POI; Spring JavaMailSender + Thymeleaf.

**Skill improvement notes:** `modernize-discover` writes §8 "Findings pre-answered for downstream phases" feed-forward table; `modernize-decompose` requires reading legacy code per epic and reserves `AskUserQuestion` for judgment-only calls. See [[feedback-derive-before-asking]] for the principle. **Refine and implement skills also default to autonomy** — `AskUserQuestion` reserved for explicit escalation triggers in their SKILL.md (e.g. parity-test failure that would require behavior change, missing dependency, contradiction between acceptance criteria and design).

**Gitignore note:** `.claude/` is ignored except `.claude/skills/` and `.claude/agents/` — both are version-controlled deliberate artifacts; the rest of `.claude/` (locks, session state) stays out of the repo. **Verify the `.claude/agents/` exception exists in `.gitignore` before committing** — added 2026-05-14 alongside the new agent definitions.

Related: [[fls-e2e-state]] — the e2e suite is the most reliable feature inventory available and is referenced from the discovery doc as the parity baseline.
