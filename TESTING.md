# TESTING.md — Build, start, and test FLS on Linux (Mono + SQL Server in Docker)

Manual playbook to reproduce the happy-path demo from scratch on a fresh Ubuntu host without Microsoft licenses. Confirmed working against Ubuntu 25.10 + Mono 6.12.0.199 + SQL Server 2022 Developer Edition.

For the **mental model** of what each piece does, see [SERVER.md](SERVER.md) and [CLIENT.md](CLIENT.md). For the **fast-path summary** read [CLAUDE.md](CLAUDE.md). This document is the step-by-step instructions.

## Acceptance test

The system is "running" when this sequence succeeds end-to-end (the T3 sequence):

1. `POST /Token` with `username=testclubadmin&password=s` returns a bearer token.
2. `GET /api/v1/users/my` with that bearer token returns the seeded admin's profile.
3. `GET /api/v1/flights/{flightId}` returns flight details including `GliderFlightDetailsData.FlightComment`.
4. `PUT /api/v1/flights/{flightId}` with a modified body returns 200 OK.
5. Re-reading the flight shows the modified `FlightComment`.

If all five pass, the server (Mono + EF6 + SQL Server) is fully wired. The client (`flsweb`) is verified separately by producing a webpack bundle.

## Prerequisites

- Linux x86_64 (any distro that has Mono 6.12 in its repos or via the official Mono apt repo). Tested on Ubuntu 25.10 "questing".
- Docker Engine. ~2 GB RAM free for SQL Server.
- ~5 GB free disk for: SQL Server image (~1.5 GB), NuGet packages (~600 MB), node_modules (~158 MB).
- Outbound HTTPS to: `mcr.microsoft.com`, `api.nuget.org`, `registry.npmjs.org`, `registry.yarnpkg.com`, `nodejs.org`, `raw.githubusercontent.com`, `github.com`.

This guide assumes the two repos live at:

```
/c/Users/roman/IdeaProjects/fls/
├── flsserver/   (C# backend, on the linux-demo branch)
└── flsweb/      (AngularJS client)
```

Adjust paths for your machine.

## One-time setup

### 1. Install Mono

```bash
sudo apt-get update
sudo apt-get install -y mono-complete
mono --version    # expect 6.12.x
xbuild /version   # MSBuild-compatible build tool bundled with Mono
```

On distros where `download.mono-project.com` still serves packages for your release, the official Mono apt repo gives the same 6.12.x. On Ubuntu 25.10 (questing) the distro package is the simplest.

`mono-complete` includes:
- `mono` (the runtime)
- `xbuild` (MSBuild-compatible build tool — Mono ships `msbuild` separately on some platforms, but `xbuild` handles .NET 4.5 + `packages.config` projects fine)
- `mcs` (C# compiler)
- All `4.5-api` reference assemblies under `/usr/lib/mono/4.5-api/`

### 2. Install the NuGet CLI

```bash
sudo curl -sSL -o /usr/local/bin/nuget.exe https://dist.nuget.org/win-x86-commandline/latest/nuget.exe
mono /usr/local/bin/nuget.exe help | head -1
```

(We run `nuget.exe` under Mono. There's no native nuget package for most Linux distros.)

### 3. Install Node 8 via nvm (only for the `flsweb` bundle build)

Webpack 1 + the pinned 2016-era babel/karma stack only works under Node 8. Use `nvm` so you can keep your real Node alongside.

```bash
unset NPM_CONFIG_PREFIX  # nvm conflicts with this — must be unset
curl -sSo- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm install 8
nvm use 8
node --version    # v8.17.0
npm install --global yarn@1
yarn --version    # 1.22.x
```

To persist across new shells, add to `~/.bashrc` (or `/etc/profile.d/...`):

```bash
unset NPM_CONFIG_PREFIX
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
```

### 4. Confirm Docker

```bash
docker --version          # 27+ recommended
docker run --rm hello-world
```

## Milestone 1 — Database

### 1.1 Start SQL Server

```bash
docker run -d --name fls-mssql \
  -e ACCEPT_EULA=Y \
  -e MSSQL_SA_PASSWORD='Demo#FLS#2026' \
  -e MSSQL_PID=Developer \
  -p 1433:1433 \
  mcr.microsoft.com/mssql/server:2022-latest
```

If you want persistence across container restarts add `-v mssql-fls-data:/var/opt/mssql` (and `docker volume create mssql-fls-data` first). In some sandboxed Docker setups (Docker Desktop with non-shared paths) the named volume may be rejected — running ephemeral as shown is fine for the demo.

Wait ~10 seconds, then verify:

```bash
docker exec fls-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Demo#FLS#2026' -C -Q "SELECT @@VERSION"
```

### 1.2 Apply the schema and seed data

```bash
cd /c/Users/roman/IdeaProjects/fls/flsserver/database/FLSTest

# Copy script trees into the container (sqlcmd needs them locally)
docker exec fls-mssql mkdir -p /tmp/sql
docker cp "1 create" fls-mssql:/tmp/sql/
docker cp "2 alter"  fls-mssql:/tmp/sql/
docker cp "3 insert" fls-mssql:/tmp/sql/

SQLCMD="docker exec fls-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P Demo#FLS#2026 -C"
```

**Create the database** — the bundled `1 Create Database.sql` hardcodes Windows file paths, so skip it and just create with defaults:

```bash
$SQLCMD -Q "CREATE DATABASE [FLSTest]"
```

**Apply the base schema** (`2 Alter Database.sql` — defines the 40 base tables):

```bash
$SQLCMD -d FLSTest -i "/tmp/sql/2 alter/2 Alter Database.sql"
```

**Apply DBUpdate scripts in semantic-version order** (~59 scripts, brings schema to ~59 tables):

```bash
cd "2 alter"
SORTED=$(ls DBUpdate_v*.sql | awk '{ ver=$0; sub(/^DBUpdate_v/,"",ver); sub(/\.sql$/,"",ver); print ver"\t"$0 }' | sort -V | cut -f2)
for f in $SORTED; do
  $SQLCMD -d FLSTest -i "/tmp/sql/2 alter/$f" 2>&1 | grep -E 'Msg [0-9]+, Level (1[6-9]|2[0-5])' | head -3
done
```

(Some DBUpdates report "constraint already exists" or similar — ~20 errors total across all scripts, all benign idempotency cases when applied on top of the current base schema.)

**Apply seed data**:

```bash
for f in \
    "3 Insert Static Data.sql" \
    "4 or 5 Insert Test Data.sql" \
    "6 Insert Test Flights.sql" \
    "7 Create Logins FLSTest.sql" \
    "10 insert internationalisation values.sql" \
    "90 Insert EmailTemplates.sql" \
    "99 Insert SystemData.sql" \
    "100 Insert AccountingRuleFilters.sql" ; do
  echo "=== $f ==="
  $SQLCMD -d FLSTest -i "/tmp/sql/3 insert/$f" 2>&1 | grep -E 'Msg [0-9]+, Level (1[6-9]|2[0-5])' | head -3
done
```

Skip `7a Delete Logins FLSTest.sql` (that's a teardown script).

### 1.3 Verify the seed

```bash
$SQLCMD -d FLSTest -Q "SELECT Users=(SELECT COUNT(*) FROM Users), Clubs=(SELECT COUNT(*) FROM Clubs), Flights=(SELECT COUNT(*) FROM Flights), Aircrafts=(SELECT COUNT(*) FROM Aircrafts);"
```

Expected: **Users=4, Clubs=2, Flights=5, Aircrafts=15**. The admin login is `testclubadmin` (password `s`) belonging to the Test-Club (`ClubId=0FA7B76F-47BA-4138-8F96-671400FD7C83`).

> **Note for the Playwright e2e suite.** The steps above are the manual / dev playbook (non-deterministic dates from `SYSDATETIME()`, hand-curated assertions). The e2e suite instead uses **`e2e/scripts/seed.sh`** which drops and recreates `FLSTest`, re-applies the same schema + static seed files, and then layers `flsserver/database/FLSTest/3 insert/_test-fixture.sql` on top — that fixture anchors every timestamp to a fixed `2026-01-01` base (so time-gated states like `Locked` and `DeliveryPrepared` are reachable without clock manipulation), adds a second club for multi-tenancy tests, seeds `AccountingRuleFilters` / `PersonCategories` / a 30-day-old historical flight for the test club, and points `SystemData.SmtpServer` at `mailpit`. Use the manual flow when iterating against the live demo; use `bash e2e/scripts/seed.sh` before running Playwright.

## Milestone 2 — Build flsserver

### 2.1 Switch to the linux-demo branch

```bash
cd /c/Users/roman/IdeaProjects/fls/flsserver
git checkout linux-demo
```

(If the branch doesn't exist yet, create it with `git checkout -b linux-demo` and apply the changes documented in `SERVER.md`'s "How it actually works" notes, summarized at the bottom of this file.)

### 2.2 Restore NuGet packages

```bash
cd src
mono /usr/local/bin/nuget.exe restore FLS.sln
```

~66 packages, ~600 MB. Expect harmless `NU1903` vulnerability warnings for the pinned 2019-era versions.

### 2.3 Build only the projects we need

xbuild handles .NET 4.5 + `packages.config` projects without complaint. Build in dependency order — the script below stops on first failure:

```bash
cd /c/Users/roman/IdeaProjects/fls/flsserver/src
for proj in \
    FLS.Common/FLS.Common.csproj \
    FLS.Server.Interfaces/FLS.Server.Interfaces.csproj \
    FLS.Data.WebApi/FLS.Data.WebApi.csproj \
    Alpinely.TownCrier/Alpinely.TownCrier.csproj \
    FLS.Server.Data/FLS.Server.Data.csproj \
    FLS.Server.Service/FLS.Server.Service.csproj \
    FLS.Server.Web/FLS.Server.WebApi.csproj \
    FLS.Server.Console/FLS.Server.Console.csproj ; do
  echo "=== $proj ==="
  xbuild "$proj" /p:Configuration=Debug 2>&1 | tail -3
done
```

We skip `FLS.Server.Tests`, `FLS.Workflow.Activator`, `FLS.Server.ProffixInvoiceService`, and `Foundation.ObjectHydrator` — none are on the T3 path.

Expect warnings (`CS1701` assembly-version mismatches, `CS0219` unused-variable, NuGet vulnerability advisories) but **0 errors**.

### 2.4 Drop the EF SqlServer provider DLL into the output

This DLL is dynamically loaded by EntityFramework at runtime and isn't picked up by xbuild's reference resolution:

```bash
cp /c/Users/roman/IdeaProjects/fls/flsserver/src/packages/EntityFramework.6.2.0/lib/net45/EntityFramework.SqlServer.dll \
   /c/Users/roman/IdeaProjects/fls/flsserver/src/FLS.Server.Console/bin/Debug/
```

Without this, every request that touches EF will 500 with *"The Entity Framework provider type 'System.Data.Entity.SqlServer.SqlProviderServices, EntityFramework.SqlServer' could not be loaded"*.

## Milestone 3 — Run the server

```bash
cd /c/Users/roman/IdeaProjects/fls/flsserver/src/FLS.Server.Console/bin/Debug
mono FLS.Server.Console.exe
```

Expected console output:

```
FLS Server starting on http://localhost:25567/
MonoStartup.Configuration called
SetDataProtectionProvider done
OAuthOptions.AccessTokenFormat set to Microsoft.Owin.Security.DataHandler.TicketDataFormat
Create new FLS.Server.WebApi.UnityConfig.UnityContainer: ...
FLS Server READY on http://localhost:25567/
Press Ctrl+C to stop
```

To run in the background and capture logs:

```bash
mono FLS.Server.Console.exe > /tmp/fls-server.log 2>&1 &
```

The listen URL can be overridden with `FLS_LISTEN_URL=http://0.0.0.0:25567/ mono FLS.Server.Console.exe` (the default `http://localhost:25567/` binds loopback only).

### 3.1 Smoke test — public endpoint (no auth)

```bash
curl -sS -i http://localhost:25567/api/v1/countries | head -10
```

Expect `HTTP/1.1 200 OK` and a JSON array of seeded countries.

### 3.2 Get a bearer token

```bash
TOKEN=$(curl -sS -X POST http://localhost:25567/Token \
          -d "grant_type=password&username=testclubadmin&password=s" \
          -H "Content-Type: application/x-www-form-urlencoded" \
        | jq -r .access_token)
echo "${TOKEN:0:40}..."
```

A 14-day-lived bearer token comes back signed with our custom AES-based data protector (see `FLS.Server.Console/SimpleDataProtector.cs`).

## Milestone 4 — Run the T3 round-trip

```bash
# Read current user profile
curl -sS -H "Authorization: Bearer $TOKEN" \
     http://localhost:25567/api/v1/users/my | jq '{Username,FriendlyName,ClubId,EmailConfirmed}'

# List glider flight overviews
curl -sS -H "Authorization: Bearer $TOKEN" \
     http://localhost:25567/api/v1/flights/gliderflights/overview | jq 'length'

# Pick one of the seeded flights
FLIGHT_ID="728a5199-3e1e-43a6-970a-c3cd741884ff"  # the "PAX flight" test flight

# Read detail
curl -sS -H "Authorization: Bearer $TOKEN" \
     "http://localhost:25567/api/v1/flights/$FLIGHT_ID" > /tmp/flight.json
jq .GliderFlightDetailsData.FlightComment /tmp/flight.json    # should be "PAX flight"

# Modify and PUT
jq '.GliderFlightDetailsData.FlightComment = "edited via Mono+Linux at \(now | todate)"' \
   /tmp/flight.json > /tmp/flight-modified.json

curl -sS -i -X PUT \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d @/tmp/flight-modified.json \
     "http://localhost:25567/api/v1/flights/$FLIGHT_ID" | head -5    # expect HTTP/1.1 200 OK

# Verify the change persisted
curl -sS -H "Authorization: Bearer $TOKEN" \
     "http://localhost:25567/api/v1/flights/$FLIGHT_ID" \
  | jq .GliderFlightDetailsData.FlightComment
```

If the final `jq` line prints `"edited via Mono+Linux at ..."`, **T3 passes**.

## Milestone 5 — Build flsweb

```bash
unset NPM_CONFIG_PREFIX
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use 8

# (Recommended) build in a Linux-native path to avoid symlink/permission quirks
# on Windows-mounted filesystems
rm -rf /tmp/flsweb-build
cp -r /c/Users/roman/IdeaProjects/fls/flsweb /tmp/flsweb-build
cd /tmp/flsweb-build

# Two case-sensitivity bug fixes needed if you didn't pull them from the linux-demo branch:
sed -i "s|'./TryflightController'|'./TryFlightController'|"           src/tryflight/TryFlightModule.js
sed -i "s|'./PassengerflightController'|'./PassengerFlightController'|" src/passengerflight/PassengerFlightModule.js

yarn install --network-timeout 600000
# `microtime` (an optional native dep) prints node-gyp errors due to Python 3.10+ removing
# collections.MutableSet. Safe to ignore — it's optional.

yarn run bundle
ls -la dist/
```

Expect a `dist/bundle.<hash>.js` plus an `index.html` and various font/image assets (~5 MB total). Bundle time: ~15 seconds.

## Tear-down

```bash
# Stop the server
pkill -f FLS.Server.Console.exe

# Stop the database (data lost unless you used a named volume)
docker rm -f fls-mssql
docker volume rm mssql-fls-data 2>/dev/null    # only if you created one
```

## Reference: changes on the `linux-demo` branch

Everything below is committed on `flsserver`'s `linux-demo` branch. If you want to redo from scratch, this is the minimum diff:

### `FLS.Server.Service/Reporting/FlightReportService.cs`

Remove the vestigial `using System.Diagnostics.Eventing.Reader;` — Windows-only Event Log API, never actually used.

### `FLS.Server.Web/Startup.cs`

Comment out `[assembly: OwinStartup(typeof(Startup))]` — otherwise the OWIN loader uses it ahead of our explicit `MonoStartup` type parameter and our Mono-safe data protector is never wired up.

### `FLS.Server.Web/App_Start/UnityConfig.cs`

Replace the unconditional `HttpContext.Current.GetOwinContext()...` registration of `IAuthenticationManager` with a null-guarded version. Under self-host there is no `HttpContext.Current`; the registration is now lazy and only throws if someone actually resolves it (which the T3 flow doesn't).

### `Alpinely.TownCrier/Alpinely.TownCrier.csproj`

Flip `<TreatWarningsAsErrors>true</TreatWarningsAsErrors>` to `false`. Mono's compiler treats `System.Net.Mail.SmtpClient` as obsolete and rejects the whole project on warnings-as-errors.

### New project `FLS.Server.Console/`

- `Program.cs` — `WebApp.Start<MonoStartup>(url)` console entry.
- `MonoStartup.cs` — pre-installs `SimpleDataProtectionProvider` and pre-fills `OAuthAuthorizationServerOptions.AccessTokenFormat`, then calls into the existing `Startup.Configuration(app)`.
- `SimpleDataProtector.cs` — AES-CBC IDataProtector replacement for the Windows-only `DpapiDataProtector` (which Mono can't type-load).
- `App.config` — connection string pointing at `localhost,1433`, EF6 provider section, all the binding redirects from `Web.config` that matter at runtime, and the same `<nlog include file="${basedir}/NLog.config" />` pattern.
- `NLog.config` (copied from `FLS.Server.Web`) — its Windows-style `internalLogFile="c:\temp\nlog-internal.log"` is harmless (NLog silently fails to write to it on Linux).
- `FLS.Server.Console.csproj` — references `FLS.Server.Web` (to pull in controllers) and the three OWIN packages already restored: `Microsoft.Owin`, `Microsoft.Owin.Hosting`, `Microsoft.Owin.Host.HttpListener` — plus `Microsoft.Owin.Security` and `Microsoft.Owin.Security.OAuth` for the data protector bridge.

> **Namespace gotcha**: the project uses namespace `FLS.Server.MonoHost` (not `FLS.Server.Console`). The latter collides with the `System.Console` static class — `WebApp.Start<MonoStartup>` silently fails to resolve the type via reflection, falls back to assembly scanning, and ends up running `Startup` directly without our Mono patches. Don't rename.

### Note on the EF6 provider DLL

`xbuild` doesn't auto-copy `EntityFramework.SqlServer.dll` because no project references it directly (EF6 loads it via the `<entityFramework>` config section). Either:
- Copy it manually as in step 2.4, **or**
- Add an explicit `<Reference>` to `EntityFramework.SqlServer.dll` in `FLS.Server.Console.csproj` with `<Private>True</Private>` (the CopyLocal hint), **or**
- Use post-build `<Content Include="..\packages\EntityFramework.6.2.0\lib\net45\EntityFramework.SqlServer.dll"><CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory></Content>` in the csproj.

The manual copy is fine for the demo.

### Note on the flsweb case-sensitivity fixes

Two relative imports in `flsweb/src/` used the wrong casing for `*Controller` files. On Windows this worked (case-insensitive filesystem); on Linux Webpack fails to resolve. The fix is in the source files themselves; the `linux-demo` branch on the flsserver side does not touch flsweb, but the equivalent flsweb-side patch is in the second `sed` block of Milestone 5.

## Troubleshooting

| Symptom                                                                                                            | Cause / fix                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `xbuild` errors out on `Microsoft.Common.targets` with "OutputPath not set"                                        | Pass `/p:Configuration=Debug` explicitly. xbuild needs the configuration to pick the right `OutputPath` property group.                         |
| Server starts but every authenticated request returns 500 with "EntityFramework.SqlServer ... could not be loaded" | `EntityFramework.SqlServer.dll` missing in bin/Debug. See Milestone 2.4.                                                                       |
| Server crashes on startup with `TypeLoadException: DpapiDataProtector`                                             | OWIN bearer middleware tried to use the Windows-only `DpapiDataProtector`. Means `MonoStartup` didn't run — most likely the `[assembly: OwinStartup]` attribute is still present in a freshly-built `FLS.Server.WebApi.dll`. Verify the attribute is commented in `Startup.cs`, force a clean rebuild (`rm -rf FLS.Server.Web/obj FLS.Server.Web/bin`), and re-run. |
| `POST /Token` returns 500 with empty body                                                                          | Usually means Unity couldn't resolve `IdentityUserManager` because EF couldn't connect. Check `docker logs fls-mssql` and the connection string in `FLS.Server.Console/App.config`. |
| Stale log output, MonoStartup output missing                                                                       | Zombie Mono process from a previous run is still holding the log file. `pkill -9 mono` and re-run.                                              |
| `yarn install` fails with `EIO: i/o error, rmdir 'node_modules/.bin'`                                              | Yarn 1 can't make symlinks on some Windows-mounted volumes. Copy the source to a Linux-native path (`/tmp/flsweb-build`) and install there.    |
| `node-gyp` errors during `yarn install` about `MutableSet` or `node-gyp` itself                                    | Native optional dep (`microtime`) failing to compile under Python 3.10+. Safe to ignore — yarn marks it optional.                              |
| `nvm install` complains about `NPM_CONFIG_PREFIX`                                                                  | `unset NPM_CONFIG_PREFIX` before sourcing `nvm.sh`, and add the unset to your shell rc file.                                                    |
| Webpack errors `Cannot resolve './TryflightController'`                                                            | Linux is case-sensitive. Apply the two `sed` fixes in Milestone 5.                                                                              |

## What this demo does *not* prove

- Workflow jobs (`DeliveryCreationJob` etc.) running end-to-end. T3 only exercises the synchronous request path.
- Email send (`EmailSendService`) actually delivering. No SMTP is configured.
- Excel/Delivery export (`ExcelExporter`, `DeliveryMailExportJob`). EPPlus pulls in System.Drawing for charts/images; for the demo we use `libgdiplus`-free code paths only.
- Proffix invoice sync. `FLS.Server.ProffixInvoiceService` is an empty stub; the real adapter lives in a separate repo.
- Multi-user concurrency, performance, or SSL. The console host listens on plain HTTP only.
- Time-gated state transitions (Locked → DeliveryPrepared) — would need backdated seed data or clock manipulation.
