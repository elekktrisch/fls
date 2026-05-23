---
name: fls-server-ipv6-binding
description: Mono FLS server binds IPv6-only by default; webpack-dev-server proxy fails with 502 unless server listens on wildcard
metadata: 
  node_type: memory
  type: project
  originSessionId: f5c1d549-7e75-4e17-961c-0fb6968be65b
---

The Mono FLS server (`mono FLS.Server.Console.exe`) defaults to `FLS_LISTEN_URL=http://localhost:25567/`, and on this Linux sandbox `/etc/hosts` resolves `localhost` to `::1` only — so the listener binds IPv6 only. The webpack-dev-server proxy in `flsweb` uses Node's resolver which prefers IPv4 127.0.0.1, hitting 502 on every `/api/v1/*` and `/Token` request.

**Why:** Node's resolution order and Mono HttpListener's host validation together produce a silent failure mode — direct curl to `localhost:25567` works, but the proxy to `127.0.0.1` doesn't.

**How to apply:** When starting the FLS server for any test or dev work that goes through the webpack proxy, use `FLS_LISTEN_URL="http://*:25567/"` (wildcard). Do NOT use `http://0.0.0.0:25567/` — Mono's HttpListener accepts the bind but then rejects requests whose Host header doesn't equal `0.0.0.0` with `400 Bad Request (Invalid host)`. The `*` form skips host validation.

Related: [[fls-e2e-setup]]
