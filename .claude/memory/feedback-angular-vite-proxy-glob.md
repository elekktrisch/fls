---
name: angular-vite-proxy-glob
description: "alpenflight/web proxy.conf.json must use `/api/v1/**` (double star) — single `*` only matches one segment, silently breaks deep paths"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 4bbf0b11-51b4-4a81-99f5-87f86e433155
---

In `alpenflight/web/proxy.conf.json`, the path filter for the Spring backend must be `"/api/v1/**"`, not `"/api/v1/*"`.

**Why:** `@angular/build:dev-server` (Vite-based, used since the modernization moved off the legacy webpack builder) treats `*` as a micromatch glob that does NOT cross `/`. So `"/api/v1/*"` matches `/api/v1/clubs` (single segment) but silently lets `/api/v1/admin/locations/clb-xxx` and any other 2+ segment path fall through to the SPA's `index.html`. Failure mode is hard to spot: the network tab shows `200 OK` with HTML body, then the generated client's JSON parse errors → component renders a generic "failed to load" banner. The legacy `@angular-devkit/build-angular:dev-server` (webpack) treated bare prefixes as recursive, so this regressed silently when the builder switched.

**How to apply:** when adding any proxy entry to `proxy.conf.json`, default to `/<prefix>/**`. If a future entry only needs single-segment matching, justify it inline. Restart `ng serve` / `pnpm start` after editing — HMR does not re-read `proxy.conf.json`. The comment in the file itself spells this out so the next reviewer can't repeat the mistake.
