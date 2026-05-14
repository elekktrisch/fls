# FLS end-to-end tests

Playwright + TypeScript suite that exercises the full FLS stack
(SQL Server + Mono Web API + AngularJS client) as the behavioral parity
contract for the upcoming rewrite.

Docs:

- `TEST_WRITING.md` — **read this before writing a new spec.** The
  self-contained-parallel model, AngularJS quirks, timeout rules.
- `PLAN.md` — numbered spec roadmap (all rows landed).
- `SELECTORS.md` — `data-testid` contract.
- `../CLAUDE.md` / `../SERVER.md` / `../CLIENT.md` — full mental model.
- `../TESTING.md` — manual stack-up playbook (more detail than the
  quickstart below).
- `.github/workflows/e2e.yml` — CI workflow. Runs read + mutate
  projects, publishes the HTML report + failure screenshots to
  gh-pages.

## Bringing up the stack from zero

The Playwright suite needs four moving parts:

| Component                     | Port  | Brought up by                                          |
| ----------------------------- | ----- | ------------------------------------------------------ |
| SQL Server 2022 (FLSTest DB)  | 1433  | `bash e2e/scripts/dev-up.sh`                           |
| Mailpit (SMTP + Web UI)       | 1025 / 8025 | `bash e2e/scripts/dev-up.sh`                     |
| FLS Web API (Mono console)    | 25567 | manual - TESTING.md Milestone 3                        |
| flsweb webpack-dev-server     | 3000  | manual - TESTING.md Milestone 5                        |

### 1. Database + email sink

```bash
bash e2e/scripts/dev-up.sh
```

The script runs `docker compose -p fls-e2e up -d`, then waits for the
`mssql` and `mailpit` healthchecks. The `-p fls-e2e` project name keeps
this stack isolated from any pre-existing `fls-mssql` container an
individual developer might already be running.

You still need to apply the FLSTest schema + seed by hand the first time
(see TESTING.md Milestone 1 - copy `database/FLSTest/` into the container,
create the DB, run `2 Alter Database.sql`, the `DBUpdate_v*.sql` series, and
the `3 insert/` scripts).

### 2. FLS Web API

TESTING.md Milestone 3 — once built:

```bash
cd flsserver/src/FLS.Server.Console/bin/Debug
FLS_LISTEN_URL="http://*:25567/" mono FLS.Server.Console.exe
```

(If you need to build from scratch, follow Milestone 2 first - NuGet
restore, `xbuild`, drop in `EntityFramework.SqlServer.dll`.)

### 3. flsweb dev-server

TESTING.md Milestone 5 — once the source tree is at `/tmp/flsweb-build`
with the two case-sensitivity fixes applied:

```bash
cd /tmp/flsweb-build
yarn start
```

The dev-server proxies `/api/*` and `/Token` to `http://localhost:25567/`.

## Running the suite

```bash
cd e2e
npx playwright test
```

The `webServer` block in `playwright.config.ts` will:

- Detect the FLS API on `:25567` and the dev-server on `:3000`. If both
  respond, it reuses them (`reuseExistingServer: true`).
- Otherwise spawn them via the configured `command` and wait up to 180s
  for each to come up.

To run a single spec or test:

```bash
npx playwright test tests/02-authenticated.spec.ts
npx playwright test -g 'flights list'
```

Reports land at `/tmp/fls-e2e-report` (HTML) and `/tmp/fls-e2e-results`
(traces + screenshots on failure).

## Tearing down

```bash
bash e2e/scripts/dev-down.sh
```

This runs `docker compose -p fls-e2e down -v` (mssql + mailpit gone, their
volumes wiped). The locally-started Mono server and yarn dev-server are
NOT stopped by this script - kill them manually:

```bash
pkill -f FLS.Server.Console.exe
pkill -f 'webpack-dev-server'
```

## Mailpit

Mailpit is the SMTP sink for email-related specs (see the planning doc -
task #24 owns the `e2e/mailpit.ts` helper). Once the stack is up:

- **SMTP** — point `SystemData.SmtpServer` at `localhost`, port `1025`, no
  auth, no SSL.
- **Web UI** — browse messages at <http://localhost:8025/>.
- **REST API** — `curl http://localhost:8025/api/v1/messages` to list,
  `DELETE` the same URL to clear between tests.
