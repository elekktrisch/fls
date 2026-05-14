---
id: S-046
title: Helm/Kustomize manifest stub mirroring compose topology
epic: E-05
status: todo
depends_on: [S-039, S-040, S-041]
acceptance:
  - Manifests under `next/ops/k8s/` mirror the compose topology: Deployment + Service per app, StatefulSet for Postgres + Keycloak, ConfigMap/Secret for env, Ingress for the proxy.
  - `kubectl apply -k next/ops/k8s/` against a kind cluster brings the stack up.
  - Smoke test: same T3-equivalent that passes against compose passes against the kind cluster.
estimate: M
adr_refs: [0010]
parity_test: none
---

## Context
ADR 0010 follow-up. K8s manifests committed *before* migration so the migration is a tested artifact when triggered.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Decide Helm vs. Kustomize (Kustomize recommended — closer to compose's "config + overlays" model, no templating language).
- [ ] Author manifests.
- [ ] Test against kind locally.

## Notes
Don't tune the manifests for production scale — they're a starting point. Production-ready tuning (HPA, PDBs, network policies) is part of the actual migration.
