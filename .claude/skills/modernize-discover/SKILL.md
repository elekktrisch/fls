---
name: modernize-discover
description: Phase 1 — ingest legacy code + docs into docs/modernization/01-current-state.md (feature inventory, architecture, integrations, risks). Trigger: /modernize-discover.
---

# Phase 1 — Discovery

You are running phase 1 of a four-phase modernization workflow. Your job is to produce **one artifact**: `docs/modernization/01-current-state.md`. It will be read by every later phase, so it must be dense, factual, and skimmable.

## Preconditions

1. Read `docs/modernization/00-seed.md` first. It contains the project's strategic anchors, sacred cows, and known integration hotspots. **Do not re-derive what the seed already states** — cite it instead.
2. Read `docs/modernization/README.md` to confirm the workflow's overall shape.
3. If `01-current-state.md` already exists, ask the user whether to (a) regenerate from scratch, (b) refresh sections that look stale, or (c) abort. Do not overwrite silently.

## Inputs to ingest

Read or grep — do not skim — these sources:

- All `*.md` at the repo root (typically `CLAUDE.md` and any project-specific deep-dive docs the seed points at).
- Top-level repo structure (one `ls` at the root and one or two levels deep into each top-level code folder).
- The test suite, especially e2e — it is usually the most reliable feature inventory.
- Backend: list of controllers/handlers, service classes, scheduled jobs, auth setup, DI registrations, DB migration scripts.
- Frontend: list of feature modules/routes, auth service, API call sites, build/dev-server config.
- `package.json` / `*.csproj` / `*.sln` for tooling versions and vendored vs. packaged dependencies.

Prefer one wide pass with multiple parallel tool calls over many narrow sequential ones. You are building a map, not debugging.

## What to write

`docs/modernization/01-current-state.md` with the following sections in order. Lengths are targets, not hard limits — be denser if the system is small, more thorough if it is sprawling.

### 1. Executive snapshot (≤ 200 words)
What this system is, who uses it, what it does in one paragraph. Stack one-liner: language + framework + DB + auth + deployment. Lifecycle stage (greenfield, mature, legacy, end-of-life).

### 2. Feature inventory
A flat list grouped by domain. Each row: feature name, where it lives (path), who uses it (admin / end-user / external integration / scheduled job), and the e2e/unit test that exercises it (or "no test"). This becomes the parity checklist for the rewrite.

### 3. Architecture digest
Bullet the load-bearing patterns the rewrite must understand. Reference, don't repeat, the project docs. Highlight any pattern the seed flags as a sacred cow.

### 4. Integration map
Inbound (who calls us, how, with what auth) and outbound (what external systems we call or feed). For each, name the contract surface (REST path / SOAP envelope / SQL view / file drop / mailbox) and the owning team/repo if known.

### 5. Data model summary
Don't list every table — list the **clusters** (e.g., "identity & access", "flight operations", "billing", "master data"). For each cluster: the central entities, the cardinality of the largest ones if knowable, and any cross-cluster references that constrain how you can split the schema.

### 6. Build, test, and ops surface
Build tools and versions. Test frameworks. CI status if visible. Deployment target. Anything pinned to a specific OS, runtime version, or commercial-license boundary.

### 7. Risk hotspots
Each risk is one paragraph: what it is, why it matters for a rewrite, and what evidence in the code suggests it. Include risks flagged by the seed (don't re-derive) and any new ones you find. Examples of categories: licensing, multi-tenancy enforcement, undocumented runtime behavior, hand-rolled migration paths, schema/code coupling, external integrations with separate ownership, security defaults (open CORS, weak token rotation).

### 8. Findings pre-answered for downstream phases
**This section is the contract with phases 2 and 3.** The point is that *they should not re-derive what the code already tells us*. Read the legacy code well enough here that the vision and ADR phases do not have to ask the user questions the code can already answer.

A table — one row per finding — with these columns:

| Finding | Current-state fact | Consumes in |
|---|---|---|
| Auth shape | (token type, lifetime, refresh present?, where stored, 401 handling) | phase-3 auth ADR |
| Background-job mechanism | (in-process / cron / external scheduler, dispatch pattern, idempotency posture) | phase-3 jobs ADR |
| Email infrastructure | (mail library, templating library + license, SMTP relay shape) | phase-3 email ADR |
| Report / file export library | (library name + version + license posture; concrete consumers if any) | phase-3 export ADR |
| i18n approach | (server-loaded / client-bundled / mixed, locale source) | phase-2 i18n constraint candidate |
| Tenancy enforcement | (filter mechanism, where it lives, density of call sites) | phase-2 sacred-cow + phase-3 tenancy ADR |
| Migration tooling | (auto-managed by ORM / hand-rolled / dedicated tool, count of scripts) | phase-3 migration-tool ADR |
| Observability today | (log lib + destination, metrics, traces, error tracking) | phase-3 observability ADR |
| API surface | (REST/GraphQL/RPC, endpoint count, public consumers known) | phase-3 API-shape ADR |
| Build + deploy shape | (build tool, target runtime, hosting model) | phase-3 backend & hosting ADRs |
| Migration cost markers | (controllers / services / entities / migrations / templates — concrete counts) | phase-4 estimates |

Each finding cites the file(s) supporting it. The downstream skills are instructed to read this section before drafting questions.

### 9. Open questions for phase 2
A bulleted list of questions the user needs to answer in the **vision & constraints** phase. These are the things you found that you could not decide on your own — non-functional requirements, target SLOs, acceptable downtime windows, team skills, hosting constraints, regulatory or compliance obligations. **Anything answered by §8 does not belong here.**

## Quality bar

- Cite file paths (and line numbers when load-bearing) for non-obvious claims. A reviewer should be able to verify each claim in under a minute.
- No marketing language. No "robust," "comprehensive," "modern." State facts.
- No re-derivation of seed content — link to the seed.
- Length target: 600–1500 lines for a large system, fewer if the system is small. Density over verbosity.
- Idempotent: re-running on an unchanged repo should produce a near-identical file.

## When you are done

1. Confirm the file exists and the sections are in order.
2. Print a 5-line summary to the user: total features inventoried, integration count, risk count, **pre-answered-finding count**, open-question count, suggested next command.
3. Do **not** start phase 2. The user controls pacing.
