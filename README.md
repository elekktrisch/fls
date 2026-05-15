# Flight Logging System (FLS)

[![ci](https://github.com/elekktrisch/fls/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/elekktrisch/fls/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](flsserver/LICENSE)
[![modernization](https://img.shields.io/badge/modernization-planning%20complete-blue)](docs/modernization/)

A multi-tenant system for managing glider and motor flight operations — reservations, flight logging, planning, accounting/invoicing exports, and email workflows.

This repository bundles the two independently-versioned components plus an end-to-end test harness that boots both via docker-compose.

The codebase is split into three layers — legacy, bridge, and rewrite — listed in the order you'd typically encounter them:

| Folder | Layer | What it is |
|---|---|---|
| [`flsserver/`](flsserver/) | legacy | ASP.NET Web API on .NET Framework 4.5, C#, EF6, OWIN. |
| [`flsweb/`](flsweb/) | legacy | AngularJS 1.4 SPA, Webpack 1, ES2015. |
| [`e2e/`](e2e/) | legacy | Playwright end-to-end tests covering the legacy stack. |
| [`docs/`](docs/) | bridge | Modernization workflow — current-state, vision, ADRs, epics, stories that describe how to get from legacy to `next/`. |
| `next/` | rewrite | Greenfield rewrite, produced story-by-story via the modernization workflow. |

## Live e2e dashboard

Each push to `main` publishes the latest Playwright run to GitHub Pages:

**https://elekktrisch.github.io/fls/**

The dashboard links to the Playwright HTML reports (read and mutate projects), the FLS server and webpack dev-server logs, and the full-page screenshots captured during the run.

## Documentation

**The root-level `*.md` files describe only the legacy stack** (`flsserver` + `flsweb`). The rewrite is planned and documented under [`docs/`](docs/) and lands as code under `next/`.

Legacy:
- [CLAUDE.md](CLAUDE.md) — surface map: project layout, build commands, conventions
- [docs/legacy/server.md](docs/legacy/server.md) — backend mental model: state machines, rules engine, jobs, multi-tenancy
- [docs/legacy/web.md](docs/legacy/web.md) — AngularJS client mental model
- [TESTING.md](TESTING.md) — running the e2e suite locally and writing new tests

Bridge → rewrite:
- [docs/modernization/README.md](docs/modernization/README.md) — modernization workflow (current-state, vision, ADRs, epics, stories) that drives what gets built in `next/`
