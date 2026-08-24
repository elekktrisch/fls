# Flight Logging System (FLS) → AlpenFlight

[![license](https://img.shields.io/badge/license-MIT-blue.svg)](flsserver/LICENSE)
[![rebuild](https://img.shields.io/badge/rebuild%202-planning-orange)](docs/modernization/)

A multi-tenant system for Swiss glider clubs. It manages flight operations: aircraft reservations,
flight logging, planning days, accounting, and invoice export to external systems.

## Status

The legacy system runs in production. A first rewrite attempt (`alpenflight/`) shipped 33 journeys,
and the operator then stopped it. **Rebuild 2 starts from the legacy reverse-engineering, and the
BMad Method drives it.** The product name stays AlpenFlight.

Rebuild 1 is archived under [`docs/attempt-1/`](docs/attempt-1/). Read it for history. It is not
a set of decisions for rebuild 2.

## Layout

| Folder | Layer | What it is |
|---|---|---|
| [`flsserver/`](flsserver/) | legacy | ASP.NET Web API on .NET Framework 4.5, C#, EF6, OWIN. Reference only. |
| [`flsweb/`](flsweb/) | legacy | AngularJS 1.4 SPA, Webpack 1, ES2015. Reference only. |
| [`e2e/`](e2e/) | legacy | Playwright suite — 43 specs over 12 categories. It drives the legacy stack, and it is the broadest behavior oracle we have. |
| [`docs/legacy/`](docs/legacy/) | reference | Mental models of the two legacy stacks. |
| [`docs/modernization/`](docs/modernization/) | reference | The legacy reverse-engineering: feature inventory, risk hotspots, schema, validation rules. |
| [`docs/attempt-1/`](docs/attempt-1/) | archive | Everything rebuild 1 produced. |
| `_bmad/` · `_bmad-output/` | rebuild 2 | The BMad installation, and the planning artifacts it writes. |
| `alpenflight/` | rebuild 2 | The rewrite. Empty today. |

## Run the legacy stack

```bash
bash e2e/scripts/dev-up.sh   # SQL Server 2022 + Mailpit
bash e2e/scripts/seed.sh     # schema + seed data into FLSTest
```

Then start the FLS Web API and the webpack dev server. See [TESTING.md](TESTING.md).

## Documentation

- [CLAUDE.md](CLAUDE.md) — the routing document: which lane you are in, and which skill to invoke
- [docs/legacy/server.md](docs/legacy/server.md) — backend mental model: state machines, rules engine, jobs, multi-tenancy
- [docs/legacy/web.md](docs/legacy/web.md) — AngularJS client mental model
- [docs/modernization/01-current-state.md](docs/modernization/01-current-state.md) — the feature inventory every epic derives from
- [TESTING.md](TESTING.md) — run the e2e suite locally, and write new tests

## Note on CI

Rebuild 1's 11 GitHub Actions workflows targeted the deleted tree. They are archived under
[`docs/attempt-1/retired-suite/workflows/`](docs/attempt-1/retired-suite/workflows/). This branch
has no CI. Rebuild 2 brings its own.
