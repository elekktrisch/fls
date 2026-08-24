---
id: S-045
title: K8s-migration trigger criteria (written threshold)
epic: E-05
status: todo
depends_on: []
acceptance:
  - A short doc under `alpenflight/ops/k8s-migration-criteria.md` enumerates ≥5 criteria covering scale, reliability, capacity, feature need, and operational need (cadence). Optionally extends with security-driven criteria (network-level tenant isolation, compliance certification, uniform secret management, forensic-retention scale).
  - Each criterion has: name · measurable numeric/boolean threshold · named observation source (Grafana panel / Uptime Kuma alert / contract clause / ops review note) · recommended response (one of `migrate` / `scale-up first` / `investigate root cause` / `revisit at next review`).
  - Doc structure: status (Draft / Pinned), owner, last-reviewed date, next-review-due date, "Why this doc exists", criteria table, "What happens when a criterion fires" runbook, "What's NOT a trigger" negative list, migration cost estimate (rough order of magnitude), references (ADR 0010, S-044, S-046).
  - Doc carries forward C4 (Swiss/EU residency) as a non-negotiable constraint on any future K8s migration path.
  - Doc carries forward ADR 0010 hygiene rules (12-factor, stateless, secrets injected, image digests) as preserved-on-migration invariants.
  - Doc is reviewed and **pinned** by the operator — explicit "I commit to these triggers" comment on the merging PR.
  - Doc cross-referenced from `alpenflight/ops/runbooks/host-setup.md` (S-044's runbook).
  - `last_reviewed` is within 30 days at merge; `next_review_due` is strictly later (recommend +12 months OR per-onboard-event).
estimate: S
adr_refs: [0010]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
refined_speculative: true
refined_speculative_at: 2026-05-15
---

## Context
ADR 0010 commits to day-1 VPS + Compose with K8s as the mid-term target. Without a written threshold, the migration either happens too early (engineering time spent on a platform that isn't earning its cost; ~5–10% K8s overhead vs. bare Docker; new attack surface) or too late (capacity / reliability / compliance already biting). This doc is the contract with future-self: **migrate when a criterion fires, not before**.

The doc is **pure documentation** — a markdown contract pinned by the operator. No code, no schema, no runtime path.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Author `alpenflight/ops/k8s-migration-criteria.md` per the Design notes §"Document shape".
- [ ] Operator reviews; pins by switching `status: Draft` → `status: Pinned` and adding the "I commit to these triggers" comment on the PR.
- [ ] Cross-link from `alpenflight/ops/runbooks/host-setup.md` (S-044 owns the runbook; if S-044 hasn't landed yet, raise a follow-up checklist item on S-044's PR).
- [ ] Pin `last_reviewed` (today) and `next_review_due` (annual or per-onboard-event).
- [ ] Run lint checks (markdownlint, link-check) as part of PR.

<!-- modernize-refine: start -->

## Design notes

### Module layout
- `alpenflight/ops/k8s-migration-criteria.md` — new operator-owned trigger doc. No code artifacts; this is a pinned operational contract.
- No server / client / DB changes.

### Document shape (commit at `alpenflight/ops/k8s-migration-criteria.md`)

```markdown
# K8s migration trigger criteria

- **Status:** Draft | Pinned            (operator flips to Pinned after review)
- **Owner:** operator
- **Last reviewed:** YYYY-MM-DD
- **Next review due:** +12 months OR per-onboard-event
- **Related:** ADR 0010, S-044 (VPS pick), S-046 (Helm/Kustomize stub)

## Why this doc exists
ADR 0010 commits to day-1 VPS + Compose with K8s as the mid-term target. Without
a written threshold, migration happens too early (wasted operability budget;
new attack surface) or too late (capacity / reliability / compliance already
biting). This doc is the contract with future-self.

## Criteria

| #  | Name                       | Measurable threshold                                              | Observation source                | Recommended response                                              | Status |
| -- | -------------------------- | ----------------------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------- | ------ |
| C1 | Scale                      | >50 clubs onboarded (4x the 12-club day-1 target)                 | Monthly billing / ops review note | Schedule migration story within 2 quarters                        | -      |
| C2 | Reliability                | SLO (99% monthly) breached in 2 months of any rolling quarter     | Uptime Kuma (S-037)               | Root-cause first; migrate only if cause is replica/multi-instance | -      |
| C3 | Resource saturation        | CPU >70% sustained 7d OR memory >80% sustained 7d OR disk >75%    | Prometheus + Grafana (ADR 0011)   | Vertical resize first; migrate at largest VPS plan                | -      |
| C4 | Feature need               | Story requires network-level tenant isolation OR multi-instance   | Explicit story / customer requirement | Migrate when the feature lands in a sprint                    | -      |
| C5 | Operational need           | Zero-downtime deploys mandated OR deploy cadence > weekly         | Deploy-frequency log + user feedback | Migrate                                                          | -      |
| C6 | Latency regression         | p95 > 750 ms (50% over NFR) sustained 7 days                      | Prometheus histogram on `/api`    | Investigate app first; migrate only if root cause is throughput   | -      |
| C7 | OOM-kill cascade           | Backend OOM-kill > 1/week OR Postgres OOM-kill > 0 in any window  | cAdvisor / node-exporter          | Vertical scale immediately; migrate if recurring at top plan      | -      |
| C-Sec1 | Network-layer tenant isolation mandated | One written legal/regulatory/contract requirement | Operator's tenant-onboarding due-diligence file | Migrate (Compose's shared bridge cannot satisfy)         | - |
| C-Sec2 | Uniform secret management required  | Rotated per-workload creds mandated OR `.env` > 30 entries | Audit report / operator inventory | Migrate (K8s Secrets + ESO + workload identity)              | - |
| C-Sec3 | Compliance certification contractual | ISO 27001 / SOC 2 customer contract or operator commitment | Signed contract / sales pipeline  | Migrate (K8s + CIS K8s baseline maps cleanly)                 | - |
| C-Sec4 | Forensic-retention scale exceeds VPS | Log retention > single-VPS disk OR incident-review gap     | Regulator / contract / incident review | Migrate (centralized aggregation + object-storage backing)  | - |

Status legend: `not triggered` · `approaching` · `triggered`.

## What happens when a criterion fires

1. Operator opens a "K8s migration evaluation" issue, citing the fired criterion + evidence (Grafana screenshot, alert, contract excerpt, incident report).
2. Apply the criterion-specific response (detail table below).
3. If response = migrate, lift the S-046 Helm/Kustomize stub into a real migration epic; schedule the rollout.
4. If response = mitigate (resize, root-cause), document the mitigation; the criterion returns to `not triggered`.
5. Operator may explicitly decide "trigger fired, decision: stay" with rationale logged — staleness in the doc is acceptable if reasoning is current.

**Defer rule:** migration is NOT initiated while another high-risk operation is in flight (active incident response, large-tenant migration in progress).

**Doc-staleness rule:** if `last_reviewed` is > 12 months old when a trigger fires, re-review the doc BEFORE acting (criteria themselves may be obsolete).

### Recommended response detail
- **C1 fires** → schedule migration in the next quarter; rough budget +€100/mo managed control plane.
- **C2 fires** → incident review first; single-VPS misconfig (DNS, certs, OOM, runaway query) is the usual cause and K8s does not fix any of them. Only escalate when root cause is genuinely replica-related.
- **C3 fires** → vertical-resize the VPS (compose-up on a bigger box is the cheapest mitigation). Migrate only when at the provider's largest plan.
- **C4 fires** → migrate when the feature ships; treat migration as part of that feature's estimate.
- **C5 fires** → migrate.
- **C6 fires** → investigate slow query, N+1, GC first (latency is app-side first); migrate only if root cause is throughput exhaustion at maxed-out node.
- **C7 fires** → immediate vertical scale; if recurring at top-tier plan, migration is justified.
- **C-Sec1 fires** → migrate (defense-in-depth; `@TenantId` remains authoritative).
- **C-Sec2 fires** → migrate.
- **C-Sec3 fires** → migrate.
- **C-Sec4 fires** → migrate.

## What is NOT a trigger
- "We have time" — pure migration engineering does not ship user value.
- "Everyone else runs K8s" — irrelevant for a 12-club, 99% SLO workload.
- "Future-proofing" — K8s value is realized at migration time, not before; manifests written without a live cluster rot.
- "K8s for HA on a single node" — misuses the platform; HA needs >1 node.
- "Resume-driven" / industry trend — not a criterion.

## Migration cost (rough order of magnitude)
- **Engineering:** 2-3 weeks solo (Helm/Kustomize manifests, CI integration, secrets, ingress, persistent volumes, ConfigMaps, observability rewire, deploy-script rewrite).
- **Managed control plane:** €100-150/mo (Exoscale SKS, Hetzner managed K8s) OR self-managed k3s/RKE2 on additional VPS ~€20-30/mo.
- **Operational:** ~1 day/quarter K8s maintenance overhead vs. compose-up steady state.
- **Perf overhead:** ~5-10% CPU vs. bare Docker (kubelet, kube-proxy, CNI, sidecars). Amortized once horizontal capacity is needed; net loss otherwise.
- **DPA implications:** switching providers (Hetzner VPS → Exoscale SKS) may require tenant DPA re-signing.
- **Net:** K8s is cheap once needed; expensive if not.

## Forward security constraints
Any future K8s migration MUST preserve:
- **C4** Swiss/EU residency on every path (Exoscale SKS CH; Hetzner CH/DE / OVHcloud EU regions only; NO US-region control plane).
- **ADR 0010 §10** (Secrets injected, not baked) — implementation switches from `.env` to K8s Secrets / External Secrets Operator, but the invariant holds.
- **ADR 0008 `@TenantId`** query-layer guard remains authoritative. NetworkPolicy is defense-in-depth, not a replacement.
- **S-027** audit-log invariants — events reach the same store (or richer) with no gap during the K8s migration window.

## Alternatives considered
- **k3s on the current VPS (single-node K8s):** cheap, but adds operational complexity without HA benefit. Consider if C5 fires but managed K8s budget is tight.
- **Fly.io / Scaleway Containers (ADR 0010 Option B):** managed scheduling without full K8s API. Rejected day-1 and as mid-term target.
- **Stay on compose forever:** acceptable while no criterion fires.

## References
- ADR 0010 — hosting + deployment shape
- S-044 — VPS provider pick
- S-046 — Helm/Kustomize stub
- S-037 — external uptime probe (observation source for C2)
- ADR 0011 — observability stack (observation source for C3, C6, C7)
```

### Integration with other stories
- **Inputs (depends_on):** none. ADR 0010 supplies framing; observation sources (Uptime Kuma, Prometheus/Grafana) come from S-037 + ADR 0011 but are *referenced*, not consumed at the moment this doc lands.
- **Outputs:**
  - **S-046 (Helm/Kustomize stub)** consumes this doc's trigger list to know *when* the stub gets promoted to a live migration epic.
  - **Any future migration epic** opens by citing the specific criterion (with evidence) that fired.
  - **S-044 (VPS pick)** is informed by C1/C3 — operator should pick a provider whose largest plan AND managed-K8s offering both exist (Exoscale, Hetzner) so vertical-resize path and migration path can stay with the same vendor.

### Alternatives considered (for this story's shape)
- **Option A (chosen): 5 sharp performance criteria + 4 security-driven criteria + explicit negative list.** Each measurable, with a named observation source and graded response (mitigate vs. migrate). Future-self answers "did it fire?" with yes/no, not judgment.
- **Option B (rejected):** Many fine-grained criteria (12+). Long lists encourage cherry-picking and erode the doc's authority.
- **Option C (rejected):** Single composite trigger ("operator decides"). Defeats the story's purpose.
- **Option D (rejected):** Time-based trigger ("review annually, decide then"). Disconnects migration from actual demand signals.

## Edge cases & hidden requirements

- **"Trigger met" but no migration budget approved** — doc requires both: criterion-fired AND budget go-ahead. Pre-record rough cost.
- **Trigger met during active incident or large-tenant migration** — explicit defer rule (above): not while another high-risk operation in flight.
- **Single transient SLO breach** vs. trend — C2 specifies *2 months in a rolling quarter*, not "ever exceeded".
- **Provider-side incident attribution** — operator's call: strict (user-perspective, count all) vs. lenient (K8s wouldn't help upstream, exclude). Pin one in the doc — open question for operator review.
- **"Onboarded" ambiguity** — distinct billed clubs vs. clubs with any active user vs. clubs with ≥1 flight in last 30 days. Pin one — open question.
- **Horizontal-scale "need" measured by what?** — C3 captures sustained CPU/memory (leading indicator, beats "after SLO broke").
- **Tenant-isolation "need" trigger** — C-Sec1 names objective signal (legal review, contract clause, regulator audit) rather than operator hunch.
- **Vertical-scale-first escape valve** — S-044's design flags 8→16 GB resize. Doc REQUIRES exhausting vertical scale first for C3 before K8s migration.
- **Doc-staleness risk** — if `last_reviewed` > 12 months, re-review BEFORE acting.
- **Concurrent counter triggers** — additive; any one fires; no AND-clause across categories unless intentional.
- **Reverse criterion: trigger met but cost-of-stay-still acceptable** — operator may explicitly decide "trigger fired, decision: stay" with rationale logged.
- **K8s-readiness violation flag (NOT a migration trigger):** if day-1 hygiene (ADR 0010 rules 1-10) drifts and a story violates "stateless containers" or "logs to stdout", the K8s-readiness assumption breaks. Flag it; don't auto-migrate.
- **Doc must be referenced from `alpenflight/ops/runbooks/host-setup.md`** so future-operator finds it. If S-044 hasn't landed when this story merges, raise a follow-up on S-044's PR to add the link.
- **Floating-IP / DNS-decoupling assumption:** S-044 notes some providers don't support floating IPs; K8s-migration switchover shape depends on this (IP-swap vs. DNS-TTL flip). Note in doc.
- **SLO breach measurement window:** C2 specifies *rolling* quarter — pin explicitly in the doc.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-045 |
|---|---|---|
| Premature K8s migration without rationale | Low | Doc requires named, observed trigger event; not aspirational. |
| Delayed K8s migration past compliance deadline | Medium | C-Sec3 captures compliance trigger with named observation source. |
| Criterion ambiguity ("isolation needed") | Medium | Every criterion has measurable threshold + named trigger source. |
| Implicit residency drop on K8s provider choice | High | Forward constraint: C4 Swiss/EU MUST be preserved on every migration path; doc enumerates acceptable providers. |
| Secrets-in-image drift during migration | Medium | Forward constraint: ADR 0010 §10 (Secrets injected, not baked) preserved on migration. |
| Doc rot | Low | `last_reviewed` + `next_review_due` parseable; doc-staleness rule. |

### Authorization
N/A — markdown artifact. Repo write access is the only gate. Operator pins via PR comment.

### Input validation
N/A — no runtime inputs. Editorial constraint: every criterion has measurable threshold, named observation source, designated trigger person/role. Criteria failing this shape are rejected at review.

### PII handling
N/A directly — doc holds no PII. Forward constraint to migration story: any future K8s migration MUST preserve C4 (Swiss/EU residency) for PII / Person tables / audit logs / backups.

### Audit-log events
N/A. Doc creation/update is git-tracked (the audit trail for the artifact itself). Forward constraint: when a criterion fires, the firing event (date, criterion #, observation source) recorded in the migration story header.

### Cross-tenant leakage
N/A at doc level. Security-driven criterion **C-Sec1** captures network-layer tenant isolation as a future migration trigger — defense-in-depth additional to `@TenantId` (S-022/S-024), not a replacement.

### OWASP applicability

Document-level:
- **A04 Insecure Design:** doc IS a design artifact. Mitigation: measurable-threshold + named-source rule rejects vague criteria.
- **A05 Security Misconfiguration:** doc enumerates that any future migration story inherits ADR 0010's day-1 hygiene as a floor.
- **A08 Software & Data Integrity:** doc is git-pinned; trigger logs append below criteria rather than silently editing thresholds.
- **A09 Logging & Monitoring Failures:** C-Sec4 captures insufficient observability / forensic-retention as a migration trigger.

Criteria-as-criteria (what fired criteria protect against once K8s is in):
- A01 Access Control: K8s adds RBAC + NetworkPolicy beyond `@TenantId`.
- A02 Cryptographic Failures: K8s enables ESO / Vault + workload identity.
- A05 Misconfiguration: K8s + CIS K8s baseline + Pod Security Admission.
- A09 Logging: K8s cluster log aggregation + retention.

### Forward security constraints to flag in the doc
(See "Forward security constraints" section in Document shape.)

## Test plan

### Pyramid
- Unit / Integration / E2E / Parity: 0 (markdown deliverable; `parity_test: none`).
- Acceptance = doc-as-deliverable review checklist + lint.

### Acceptance verification (PR-review checklist)

**Structural presence:**
- `verify-file-exists`: `alpenflight/ops/k8s-migration-criteria.md` exists.
- `verify-required-sections`: doc contains H2/H3 for Status, Owner, Last reviewed, Next review due, Criteria, "What happens when a criterion fires", "What's NOT a trigger", "Migration cost", References. Grep returns ≥9 lines.
- `verify-header-fields`: `status`, `owner`, `last_reviewed`, `next_review_due` populated (no `TBD`, no empty).

**Criteria table completeness:**
- `verify-criteria-rows`: ≥5 criteria covering at minimum scale, reliability, capacity, feature need, operational need (matching frontmatter AC).
- `verify-criteria-columns`: every row has name + measurable threshold (numeric/boolean — no fuzzy words like "many", "often", "high") + observation source (named tool / metric / event) + recommended response (closed vocabulary).
- `verify-threshold-measurability`: each threshold has `>`, `>=`, `<`, `<=`, `==`, true/false, or explicit number. Reject prose-only thresholds.
- `verify-observation-source-named`: each cell names a concrete artifact — not "monitoring" or "operator judgment" alone.
- `verify-response-controlled-vocabulary`: each `recommended response` is from `migrate` / `scale-up first` / `investigate root cause` / `revisit at next review` (or amended preamble extends vocabulary).

**Behavioral sections:**
- `verify-when-fires-section`: concrete next steps (who decides, which doc to open, which ADR supersedes ADR 0010 if migration goes).
- `verify-not-a-trigger-section`: ≥3 explicit negatives resisting drift.
- `verify-migration-cost-estimate`: concrete order-of-magnitude (engineer-weeks, €/mo). No "significant"/"moderate" alone.

**Cross-doc wiring:**
- `verify-referenced-from-runbook`: `alpenflight/ops/runbooks/host-setup.md` links to `k8s-migration-criteria.md`. Defer to S-044's PR if S-044 hasn't landed.
- `verify-adr-back-reference`: doc links to `docs/modernization/adrs/0010-*.md` in References.

**Review-cadence integrity:**
- `verify-last-reviewed-recent`: parses as ISO; within 30 days at merge.
- `verify-next-review-future`: parses as ISO; strictly after `last_reviewed`; recommend `last_reviewed + 12 months`.
- `verify-cadence-trigger`: doc states cadence (annual + on-onboard-event or equivalent).

**Operator signoff:**
- `verify-operator-signoff`: merging PR contains operator-authored comment/commit-trailer with literal `I commit to these triggers` (or project-agreed phrase). Merge gate, not doc-content check.

### Lint-style checks (low-cost)
- `markdownlint alpenflight/ops/k8s-migration-criteria.md` with repo's existing config.
- `link-check` (lychee / markdown-link-check) — verifies ADR back-reference, runbook reference, dashboard URLs.
- Date-format regex: `^\d{4}-\d{2}-\d{2}$` on `last_reviewed` and `next_review_due`.
- Controlled-vocab one-liner: extract `recommended response` column; assert every value in allowed set.

For a one-shot doc, a PR-template reviewer checklist is the right ceiling — don't build CI for one file.

### Test data + fixtures
None — no runtime fixtures. Doc-as-doc dependency: S-044's `alpenflight/ops/runbooks/host-setup.md` for back-reference.

### Coverage gaps (deferred)
- `verify-referenced-from-runbook`: blocked on S-044. Mark deferred + raise follow-up on S-044's PR.
- Actually firing a trigger (measuring `clubs_onboarded`): out of scope — future migration-execution story. Manual UAT at trigger time.
- The K8s-migration plan itself (rollout playbook): future story when a trigger fires.
- Validating observation sources emit the metric they claim: manual UAT at next ops review.

### Risks
- **Doc rots, never revisited.** Mitigation: `next_review_due` is parseable + future; add a calendar/issue reminder when merged.
- **Aspirational, not measurable thresholds.** Mitigation: `verify-threshold-measurability` + `verify-observation-source-named` reject prose-only cells.
- **"Trigger fired but we're busy" deadlock.** Mitigation: "What happens when a criterion fires" names a single decision-maker + next artifact.
- **Operator signoff is rubber-stamp.** Mitigation: signoff phrase required in a comment on the SAME PR that introduces the doc.
- **Controlled-vocabulary too rigid for real-world response.** Mitigation: amend the doc's preamble to extend the vocabulary in the same PR; don't smuggle prose into a cell.

## Performance plan

### Doc-side
N/A — markdown deliverable; no executable artifact.

### Doc MUST encode the following performance-driven criteria (above in §Document shape, C3 + C6 + C7)
- **CPU sustained > 70% over 7 days** — leading indicator.
- **Memory sustained > 80% over 7 days** — leading indicator.
- **Disk utilization > 75%** — slower-moving.
- **Network egress approaching provider cap** (Hetzner 20 TB/mo, Infomaniak metered) — trigger at ~80% of monthly budget on rolling 7-day rate.
- **Latency regression: p95 > 750 ms** (50% over NFR) for 7 consecutive days — app-side first, throughput second.
- **Container restart cascade:** backend OOM-kill > 1/week or Postgres OOM-kill > 0 — hard capacity signal.

### Recommended response per trigger
- CPU/memory: **vertical scale first**; migrate only at provider's largest plan.
- Disk: vertical scale (add disk).
- Network egress: vertical scale or provider re-evaluation; migration only if multi-region/CDN required.
- Latency: investigate app first; migrate only if root cause is throughput at maxed-out node.
- OOM-kill: immediate vertical scale; migration if recurring at top tier.

### Observation sources (so criteria are operationally measurable)
- Prometheus + Grafana panels (ADR 0011, S-035 default dashboards).
- Uptime Kuma / S-037 (external SLO probe).
- Provider-native metrics (CPU/memory/disk/network) as cross-check when in-cluster exporters degrade.

### K8s migration perf overhead
~5-10% CPU vs. bare Docker (kubelet, kube-proxy, CNI, sidecars). Amortized once horizontal capacity is genuinely needed; net loss otherwise. Documented in the migration-cost section so trigger-firing accounts for it.

<!-- modernize-refine: end -->

## Notes
Treat this doc as a contract with future-self — don't migrate to K8s "because everyone else does" or "I have free time." Migrate when criteria fire.
