# Rebuild 1 — archive

Everything the first rewrite attempt produced. **This is history, not authority.** Rebuild 2 makes
its own decisions with the BMad Method. Read anything here to learn what was tried and what it cost.
Do not treat a file here as a current decision.

## Why the archive exists

The first attempt ran from 2026-05-14 to 2026-08-23. It shipped 33 journeys (J-0 … J-33) and 112
stories into `alpenflight/`: a Java modulith server, an Angular web client, a Keycloak realm, a
Flyway database, and a legacy→migrate→Keycloak→Playwright proof chain. The operator stopped it and
chose a restart from scratch.

**The code is not in this folder.** It lives on the `main` branch:

```bash
git show main:alpenflight/                    # browse the tree
git checkout main -- alpenflight/             # restore it entirely
git log main --oneline -- alpenflight/        # read its history
```

That tree holds 2,184 files and 258,289 lines.

## What is here

| Path | What it holds |
|---|---|
| `adrs/` | 30 architecture decision records — backend language, database, auth, tenancy, layering, and more |
| `epics/` | 14 epics (E-01 … E-15) |
| `stories/` | 109 open story files, 112 in `implemented/`, plus `_ORDER.md` (the journey roadmap), `_SHIPPED.md` (33 shipped journeys with PR numbers), and `_BOYSCOUT.md` |
| `02-vision-and-constraints.md` | The vision: mobile-first, airfield hot path, self-service migration, freemium SaaS |
| `design-reference/` | The AlpenFlight UI design mockups (HTML + JSX) and screenshots |
| `privacy-notice.md` | The privacy notice source text, tied to rebuild-1 code |
| `do-suite.md` | How the retired `/do-*` skill suite worked |
| `workflow-README.md` | The modernization workflow overview |
| `retired-suite/skills/` | `do-plan`, `do-ship`, `do-task`, `do-retro`, `comment-strip` |
| `retired-suite/agents/` | `slice-carver`, `gap-hunter`, `e2e-driver`, `comment-judge` |
| `retired-suite/workflows/` | 11 GitHub Actions workflows |
| `retired-suite/ci-scripts/` | The CI helper scripts and the GitHub Pages proof gallery |
| `retired-suite/tooling/` | The Testcontainers reaper hook, the rebrand allowlist, the Qodana config |

## What did NOT come here

These stayed in `docs/modernization/` because they describe the **legacy** system, not rebuild 1:
`00-seed.md`, `01-current-state.md`, `legacy-migration-plan.md`, `legacy-tables/`,
`form-validation-parity-audit.md`.

The `legacy-oracle` agent also stayed in `.claude/agents/`. It reads legacy code, so rebuild 2 uses
it unchanged.

## The most valuable reading

If you read only three things before you plan rebuild 2:

1. **`stories/_SHIPPED.md`** — one line per shipped journey, each naming the defects that journey
   found. It is a list of the traps this domain sets.
2. **`adrs/0026-intentional-behavioral-divergences-from-legacy.md`** — where rebuild 1 chose to
   *not* match legacy, and why.
3. **`adrs/0022-modernization-primary-directives.md`** — the two rules that governed everything.
   `CLAUDE.md` carries them forward as provisional, pending ratification at `bmad-architecture`.
