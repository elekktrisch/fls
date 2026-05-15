---
id: S-044
title: VPS provider selection + provisioning
epic: E-05
status: todo
depends_on: []
acceptance:
  - Provider chosen and documented in `next/ops/vps-provider-evaluation.md` (Hetzner DE/EU, Exoscale CH, Infomaniak CH, Init7 CH, OVHcloud FR/DE — comparison matrix + recommendation).
  - Signed DPA (data processing agreement) on file with the chosen provider; region setting + snapshot storage region both verified Swiss or EU per C4.
  - Production VPS provisioned: 4 vCPU / 8 GB RAM / 80 GB SSD (NVMe where offered); Debian 13 or Ubuntu 24.04 LTS.
  - SSH key auth only (`PasswordAuthentication no`); root SSH disabled (`PermitRootLogin no`); non-root sudo user with passwordless sudo (key-gated).
  - fail2ban running with SSH jail; `unattended-upgrades` configured for the security pocket (auto-reboot at 04:00 if required).
  - UFW enabled with default-deny inbound; allowed ports `{22/tcp, 80/tcp, 443/tcp}` only.
  - Provider-level snapshots scheduled daily with ≥30-day retention (operator confirms in panel screenshot, recorded in provisioning report).
  - PTR record matches the planned hostname (required for S-091 SMTP deliverability).
  - DNS is decoupled from the VPS provider (Cloudflare or DNSimple as authoritative) so future provider migration is non-disruptive.
  - `next/ops/scripts/provision-vps.sh` is idempotent (re-running is a no-op when state already correct); tested on a throwaway VPS first.
  - `next/ops/scripts/verify-vps.sh` runs the full verification checklist (Test plan) and exits 0.
  - `docs/modernization/ops/S-044-provisioning-report.md` captures: provider, region, DC city, VPS ID, IP, SSH key fingerprint, snapshot policy ID, perf baselines (fio/sysbench/ping/docker-stats sums), all evidence.
  - External `nmap` from off-host shows only `{22, 80, 443}` open; data service ports (5432, 8080-admin, 1025, 8025, 9000, 9090, 3000) all closed/filtered.
  - Provider account has 2FA enforced (hardware key preferred); provider API tokens scoped to "create/destroy VPS + snapshot" only.
estimate: M
adr_refs: [0010]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
refined_speculative: true
refined_speculative_at: 2026-05-15
---

## Context
ADR 0010 chose "single Swiss/EU VPS + Docker Compose, K8s-ready hygiene from day one" and deferred provider selection here. This story produces the **comparison matrix + decision + the provisioned host**. It also lays the on-host substrate that S-039 (compose), S-041 (reverse proxy), S-042 (backups), S-043 (restore), S-046 (Helm/Kustomize), S-091 (SMTP relay), and S-117 (cutover) all consume.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Author `next/ops/vps-provider-evaluation.md` (the comparison matrix below + recommendation).
- [ ] Operator picks the provider; sign DPA; record region + jurisdiction.
- [ ] Author `next/ops/scripts/provision-vps.sh` (idempotent bash; 50–100 lines; behavior contract in Design notes).
- [ ] Optionally author `next/ops/cloud-init/user-data.yml` for providers that support it (Hetzner Cloud, Exoscale).
- [ ] Test `provision-vps.sh` on a throwaway VPS — assert idempotent (second run is no-op).
- [ ] Provision the production VPS; run the script; verify with `verify-vps.sh`.
- [ ] Author `next/ops/runbooks/host-setup.md` (operator manual: provision dialog, DNS records, bootstrap invocation, verification checklist, snapshot rotation, rollback hatch).
- [ ] Author `next/ops/dns/zone-records.md` (DNS records maintained at the decoupled DNS provider).
- [ ] Run perf baseline measurements (fio, sysbench, ping, docker-stats 24h sample) and commit `next/ops/vps-perf-baseline.md` — feeds S-108 and S-111.
- [ ] Test the snapshot-restore-on-throwaway drill (lightweight; full DR drill is S-043). Destroy the throwaway instance afterward.

<!-- modernize-refine: start -->

## Design notes

### Module layout

- `next/ops/vps-provider-evaluation.md` — comparison matrix + recommendation (decision artifact).
- `next/ops/runbooks/host-setup.md` — operator manual.
- `next/ops/scripts/provision-vps.sh` — idempotent bootstrap.
- `next/ops/scripts/verify-vps.sh` — verification checklist runner.
- `next/ops/cloud-init/user-data.yml` — optional, for providers supporting cloud-init.
- `next/ops/dns/zone-records.md` — authoritative DNS records (A/AAAA/PTR/CAA/TXT) at the decoupled DNS provider.
- `next/ops/vps-perf-baseline.md` — fio/sysbench/ping baselines (feeds S-108, S-111).
- `docs/modernization/ops/S-044-provisioning-report.md` — provisioning evidence (region, VPS ID, IP, SSH fingerprint, snapshot policy, perf baselines).

**No code in `next/server/` or `next/web/`.** No application changes.

### Host shape

| Aspect | Choice |
|---|---|
| Specs | 4 vCPU / 8 GB RAM / 80 GB SSD (NVMe where offered) |
| OS | Debian 13 (preferred) or Ubuntu 24.04 LTS (fallback) |
| Account | Non-root `fls` user, key-only SSH, passwordless sudo gated by SSH key; root SSH disabled |
| Firewall (UFW) | Default-deny inbound; allow `22/tcp`, `80/tcp`, `443/tcp`; default-allow outbound |
| Services baked into host (not Docker) | `openssh-server`, `ufw`, `fail2ban` (sshd jail), `unattended-upgrades` (security pocket only, auto-reboot at 04:00), `chrony`, `docker-ce` + `docker-compose-plugin` |
| Hostname | `fls-prod-01.fls.example` (placeholder — operator picks apex at DNS-cutover time) |
| TZ | `Europe/Zurich` |
| DNS | Cloudflare or DNSimple (decoupled from VPS provider) — A/AAAA/PTR/CAA |

### Provider comparison matrix

Commit as `next/ops/vps-provider-evaluation.md`. Columns: provider, region(s), spec, monthly price, snapshot retention, object-storage residency, DPA / GDPR posture, IPv6, KVM rescue, cloud-init, floating IP, control-panel rating, support quality, escape hatch, notes.

| Provider | Region(s) | Spec/Plan | Price (€/CHF) | Snapshot | Object storage | DPA | IPv6 | KVM | cloud-init | Floating IP | Panel | Support | Escape | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Hetzner Cloud** | Falkenstein DE / Helsinki FI / Nuremberg DE | CPX31: 4 vCPU AMD / 8 GB / 160 GB | ~€15/mo + ~€3 backups | Daily, 7d retention | Hetzner Object Storage (EU only) | ✓ GDPR | ✓ | ✓ | ✓ full | ✓ | 5/5 | 4/5 | snapshot DL | Lowest €/spec; **NO CH region**; meets C4 via EU |
| **Exoscale** | Zurich + Geneva (CH) | Standard.Large: 4 vCPU / 8 GB / 100 GB | ~CHF 35/mo | Configurable retention | SOS in CH (CH-DK-2 / CH-GVA-2) | ✓ Swiss | ✓ | ✓ | ✓ | ✓ Elastic IP | 4/5 | 5/5 | ✓ | **Swiss residency**; ~2x Hetzner cost |
| **Infomaniak Public Cloud** | Geneva (CH) | OpenStack `a4-ram8-disk80` | ~CHF 30/mo | Configurable | Swiss Backup (CH) | ✓ Swiss | ✓ | ✓ | ~ partial | ✗ | 3/5 (busy Horizon) | 4/5 | ✓ Glance | Swiss; OpenStack overhead |
| **Init7 EasyVPS** | Winterthur (CH) | 4 vCPU / 8 GB / 80 GB | ~CHF 40/mo | Manual + scheduled | ✗ (pair w/ CH provider) | ✓ | ✓ (excellent IPv6) | ✓ | ✓ | ✗ | 2/5 | 5/5 | snapshot DL | Small + technical; highest unit cost |
| **OVHcloud** | Strasbourg FR / Frankfurt DE | VPS-Comfort: 4 vCPU / 8 GB / 160 GB | ~€20/mo | Daily backup add-on | EU only | ✓ GDPR | ✓ | ✓ | ✓ | ✓ (Public Cloud tier) | 3/5 | 3/5 | ✓ | EU not CH; ops experience mixed |

**Primary recommendation: Hetzner Cloud — Falkenstein (DE), CPX31.** Lowest TCO meeting C4 (Swiss OR EU), best-in-class control panel + cloud-init + snapshots + floating IPs, DNS decoupled so provider migration is non-disruptive.

**Swiss-only fallback: Exoscale — Zurich, Standard.Large.** Picked if the operator's legal review insists on Swiss-borders-only; ~2x cost, operationally near-equivalent.

**Rejected for day 1:** Infomaniak (control-panel sprawl + weaker cloud-init), Init7 (highest cost + thinnest tooling), OVHcloud (ops experience inferior to Hetzner at the same EU tier).

### `provision-vps.sh` behavior contract

```bash
# Idempotent. Re-running is a no-op when state already correct.
# Run as root on a fresh Debian 13 / Ubuntu 24.04 image, via `bash -s` over SSH
# or pasted into the provider's web console.
#
# Steps (each guarded by a "is this already done?" check):
#  1. apt update + install: curl, ca-certificates, gnupg, ufw, fail2ban,
#     unattended-upgrades, chrony, jq.
#  2. Stop + disable systemd-timesyncd; enable chrony.
#  3. Create non-root user `fls` with sudo; seed authorized_keys from $FLS_SSH_PUBKEY
#     (script aborts if unset).
#  4. Harden /etc/ssh/sshd_config: PermitRootLogin no, PasswordAuthentication no,
#     KbdInteractiveAuthentication no, ChallengeResponseAuthentication no, UsePAM yes;
#     `sshd -t` then reload.
#  5. UFW: default deny inbound / allow outbound; allow 22, 80, 443; `ufw --force enable`.
#  6. Drop /etc/fail2ban/jail.local with sshd jail enabled (maxretry=5, bantime=1h);
#     restart fail2ban.
#  7. unattended-upgrades for security pocket only; enable auto-reboot at 04:00
#     (Origins-Pattern Debian-Security or Ubuntu-Security).
#  8. Set hostname from $FLS_HOSTNAME; update /etc/hosts.
#  9. Install Docker CE + compose plugin from Docker's apt repo (stable channel).
#     Add `fls` to the `docker` group.
# 10. Print verification summary: hostname, kernel, docker version, ufw status,
#     fail2ban status, chrony tracking.
#
# Required env:   FLS_HOSTNAME (e.g. fls-prod-01.fls.example), FLS_SSH_PUBKEY (ed25519).
# Optional env:   FLS_TIMEZONE (default Europe/Zurich).
```

**Alternative:** cloud-init `user-data.yml` blob for providers that support it (Hetzner Cloud, Exoscale). Same actions; consumer-side path differs.

**Rejected:** Ansible / Terraform. One host, low churn; bash + cloud-init is right-sized. The K8s migration (S-046) is the inflection point to introduce IaC.

### Snapshot strategy

- Provider snapshots: **daily auto, ≥30-day retention** (operator confirms in panel; capture screenshot in provisioning report).
- **NOT a replacement for off-site backups (S-042).** Provider snapshot lives in the same DC region as the VPS — a provider-wide incident (rare but real, e.g. OVH SBG fire 2021) takes both. S-042's off-site `pg_dump` to Swiss object storage is the real DR.

### DNS architecture

- **Decouple DNS from VPS provider** (Cloudflare or DNSimple as authoritative). Enables provider migration without DNS lock-in.
- DNS records (committed in `next/ops/dns/zone-records.md`):
  - `A` + `AAAA` for VPS IPs
  - `PTR` requested from the VPS provider's panel pointing to the hostname (S-091 SMTP)
  - `CAA 0 issue "letsencrypt.org"` to lock TLS issuance to a single CA (mitigates A02)
  - `MX` + SPF/DKIM/DMARC `TXT` later from S-091

### Integration with downstream stories

| Downstream | What it consumes from S-044 |
|---|---|
| S-039 (compose skeleton) | The `fls` non-root user (UID/GID for compose volume ownership), Docker engine + compose-plugin, UFW open-port list (80/443) for Caddy. |
| S-040 (production Dockerfile) | JVM container-memory tuning assumes the 8 GB host budget. |
| S-041 (reverse proxy) | Binds to 80/443; consumes A/AAAA records. |
| S-042 (off-site backup) | Bucket residency matches this provider's region (or sibling CH/EU provider). |
| S-043 (restore runbook) | Exercises against a freshly-provisioned twin (re-run `provision-vps.sh` + `compose up` + restore). |
| S-046 (Helm/Kustomize) | Keeps the VPS shape K8s-ready; no host-local state, no host-baked secrets, no non-containerized application services. |
| S-091 (production SMTP relay) | PTR record + reverse DNS at provider panel. |
| S-117 (DNS / reverse-proxy cutover) | This VPS is the cutover target; decoupled DNS is what S-117 flips. |
| S-118 (rollback plan) | Floating IP support enables IP-swap rollback if the operator picks a provider that supports it (Hetzner, Exoscale yes; Infomaniak/Init7 no). |

### Alternatives considered

- **Option A (chosen): Hetzner Cloud DE (EU residency) + bash bootstrap + Cloudflare DNS.** Lowest TCO meeting C4, best ops experience, escape-hatch via image export, DNS decoupled.
- **Option B (recommended fallback): Exoscale CH (Swiss residency strict).** Picked only if Swiss-borders-only required; ~2x cost.
- **Option C (rejected): Managed Kubernetes (Exoscale SKS, Hetzner K8s beta).** ADR 0010 Option C — day-1 control-plane cost unjustified at 99% SLO + single-instance.
- **Option D (rejected): Container-on-platform (Fly.io, Scaleway, Render).** ADR 0010 Option B — higher cost + platform-specific idioms.
- **Option E (rejected): Bare metal (Hetzner Robot, Init7 dedicated).** No instant snapshots; harder restore drills.
- **Option F (rejected): Multi-VPS active-active.** 99% SLO doesn't justify operational complexity at our scale.
- **Option G (rejected): Ansible/Terraform.** Single host, low churn; bash + cloud-init right-sized.

## Edge cases & hidden requirements

- **Snapshot retention floor:** AC pins ≥30 days. Some providers default to 7. Verify before commit.
- **Provider snapshot ≠ off-site backup.** Same DC region; S-042 owns the real DR.
- **Region drift:** provider may list "CH" but replicate snapshot metadata elsewhere. Verify in DPA + region-pinning toggle.
- **PTR / reverse DNS configurability:** Hetzner/Exoscale/Infomaniak via panel; OVH via support ticket. Validate before commit (S-091 needs this for SMTP deliverability).
- **Rescue / KVM console:** when SSH locks out (fail2ban self-lockout, UFW typo), only path back in. Test once at provisioning.
- **Bandwidth caps:** Hetzner 20 TB/mo soft (€1/TB over); Exoscale unmetered; Infomaniak metered. Document cap in runbook.
- **Floating IP support:** Hetzner yes; Exoscale Elastic IP; Infomaniak no. Affects S-118 rollback strategy.
- **Snapshot crash-consistency for live Postgres:** some providers' snapshots not crash-consistent; pair with `pg_dump` (S-042) regardless.
- **VPS resize path:** vertical scale (4→8 vCPU, 8→16 GB) — does provider allow live resize or stop+resize+start? Document procedure (within 99% SLO budget).
- **Cloud-init / SSH-key-at-create:** all five candidates support; verify reproducibility.
- **Provider account 2FA:** mandatory; hardware key preferred; dedicated email not shared with billing.
- **Billing failure grace:** missed credit card can suspend VPS. Confirm provider's grace + notification path.
- **DDoS protection scope:** L3/L4 default at all five; L7 needs Cloudflare in front (out of scope; flag).
- **Bootstrap script is the load-bearing DR artifact** — must be idempotent. Test before applying to prod.
- **IPv4 surcharge:** Hetzner charges per IPv4 (~€0.50/mo). Factor into TCO.
- **Greenfield host hygiene missing from AC:** chrony, logrotate, journald retention cap, swap config, kernel-update reboot policy — included in `provision-vps.sh` contract.
- **Provider DPA + customer contracts:** out-of-repo legal artifact but a cutover gate. Confirm operator's tenant-DPA template names the chosen provider as sub-processor.
- **DNS provider final pick:** Cloudflare free tier (DDoS-proxy useful if SLO bites) vs. DNSimple (paid, Swiss-friendly, cleaner API). Operator preference.
- **Provider locale:** all five offer English console; flag if any feature only in German/French.
- **VAT / billing currency:** CHF vs. EUR; cross-border B2B VAT-exempt depending on operator's registration status.
- **Compliance certifications:** ISO 27001 / SOC 2 not required by vision but nice for club due-diligence.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-044 |
|---|---|---|
| SSH brute force / credential stuffing | High | `PasswordAuthentication no`; key-only; fail2ban (3 failures / 10 min ban); optional non-22 port. |
| Default OS package vulnerabilities | Medium | `unattended-upgrades` security pocket; weekly reboot window if kernel updates require it; monitor `/var/run/reboot-required`. |
| Public services exposed unnecessarily | High | UFW default-deny inbound; only 22/80/443 open; data services bound to 127.0.0.1 or Docker internal network; external `nmap` post-provisioning. |
| Provider account compromise | High | Provider 2FA mandatory, hardware key preferred; dedicated billing email; API tokens scoped + rotated quarterly. |
| Data residency violation | High | Swiss/EU region; provider's data-processing terms reviewed; snapshot + DR replication stays CH/EU; DPA on file. |
| Snapshot / image leakage | Medium | Encrypt data volume (LUKS) so snapshots are ciphertext; OR rely on provider snapshot ACL + delete on rotation. Decision recorded. |
| Host FS unencrypted at rest | Medium-High | LUKS on `/var/lib/docker` (or `/data`) with passphrase entered at boot via console, OR provider's physical-DC attestation + encrypted backups (S-042). |
| Outbound SMTP abuse / open relay | Medium | mailpit dev/staging only; prod overlay uses external relay (S-091); UFW blocks 25/1025/8025. |
| DDoS (volumetric / L7) | Low-Med | Provider-included L3/L4; Cloudflare in front if SLO bites. |
| Stolen sudo session | Medium | Key passphrase + agent-forwarding discipline; key rotation policy; consider per-key `from=` restriction. |
| System log tampering / loss | Medium | logrotate; ship `auth.log`/`syslog` off-box (S-031). |
| TLS misissuance for the domain | Low | CAA record `0 issue "letsencrypt.org"`; HSTS via reverse proxy. |

### Authorization

N/A — operational. SSH access gated by OS-level public-key auth + sudo group membership.

### Input validation

N/A — no app inputs. Provisioning values (hostname, region, SSH pubkey) validated by provider API + `sshd`.

### PII handling

- **Postgres DB** (members, pilots, trial-flight registrants): sensitive PII; medical certs trigger FADP Art. 5 / GDPR Art. 9 special-category data. Storage: encrypted at rest (LUKS or provider equivalent), Swiss/EU region only.
- **Member email + names in mail queue:** sensitive PII; mailpit disabled in prod; external relay logs redacted to envelope-only.
- **SSH `auth.log`** (admin source IPs): low-sensitivity operator data; retained per logrotate (4 weeks); shipped to observability (S-031).
- **Provider account email + billing contact:** operator's PII; documented in DPA folder.

### Audit-log events

N/A in S-044. System-level events (sshd, sudo, apt, UFW deny hits) land in `auth.log` + `syslog` + `ufw.log` + `fail2ban.log` and rotate via logrotate. S-031 ships off-box.

### Cross-tenant leakage

N/A — VPS hosts a single deployment. **Ops note:** when running `psql` directly on the host (DB maintenance, restore drills), the operator bypasses `@TenantId`. Runbook: any manual SQL must filter by `club_id` or be limited to schema-level operations; direct DB access is itself an audit event (logged via sudo).

### OWASP applicability

- **A02 Cryptographic Failures:** TLS 1.2+ enforced by reverse proxy (S-041); at-rest encryption decision documented; backups encrypted (S-042).
- **A05 Security Misconfiguration (primary):** SSH hardening, UFW default-deny, fail2ban, unattended-upgrades, Docker daemon socket not exposed over TCP.
- **A06 Vulnerable Components:** LTS base image, kernel + OS packages patched, Docker version pinned, monthly CVE feed review.
- **A07 ID&A Failures:** SSH key-only, one key per admin, provider 2FA, API token rotation.
- **A08 Integrity Failures:** APT signature verification on; Docker images pulled by digest (S-039/S-040).
- **A09 Logging Failures:** auth/syslog/ufw rotated; placeholder hooks for S-031.

### Compliance

- **FADP** (Switzerland): residency satisfied by CH or EU (EU has adequacy).
- **GDPR Art. 28**: provider acts as processor; signed DPA required pre-prod.
- **GDPR Art. 9** (special categories — medical certs): LUKS or provider-attested at-rest encryption strongly recommended.
- **ISO 27001 / SOC 2**: not required by vision; record provider attestations for club due-diligence.

## Test plan

### Pyramid
- Unit / Integration / E2E / Parity: 0 (no app code; `parity_test: none`).
- Acceptance = a verification checklist run against the live VPS + a bootstrap dry-run on a throwaway.

### Verification checklist (`verify-vps.sh`)

**SSH + identity:**
- `ssh-key-auth-works`: `ssh -o BatchMode=yes ${OPS_USER}@${VPS_HOST} true` exits 0.
- `ssh-password-rejected`: password attempt rejected.
- `ssh-root-rejected`: `ssh root@${VPS_HOST}` refused; `sshd_config` shows `PermitRootLogin no`.
- `ops-user-exists`: `getent passwd ${OPS_USER}` returns the row.
- `ops-user-passwordless-sudo`: `sudo -n -l` shows `NOPASSWD: ALL`.

**Firewall + intrusion:**
- `ufw-active`: `sudo ufw status verbose` reports `Status: active`, default `deny (incoming)`.
- `ufw-allowed-ports`: exactly `{22/tcp, 80/tcp, 443/tcp}` ALLOW IN; no other ALLOW.
- `fail2ban-active`: `systemctl is-active fail2ban` → `active`; sshd jail reachable.
- `unattended-upgrades-active`: service active; `/etc/apt/apt.conf.d/20auto-upgrades` enables Update + Upgrade.

**Patch + locale:**
- `system-fully-patched`: `apt list --upgradable` empty.
- `timezone-correct`: `cat /etc/timezone` = `Europe/Zurich`.

**Container runtime:**
- `docker-installed`: `docker version` runs.
- `compose-plugin-installed`: `docker compose version` runs (plugin form).
- `docker-daemon-running`: `systemctl is-active docker` → `active`.
- `ops-user-in-docker-group`: `id ${OPS_USER}` includes `docker`.

**DNS:**
- `dns-forward-matches`: `dig +short A ${VPS_HOSTNAME}` = `${VPS_IP}`.
- `dns-reverse-matches`: `dig +short -x ${VPS_IP}` = `${VPS_HOSTNAME}.`
- `aaaa-optional`: if IPv6 provisioned, AAAA record matches `ip -6 addr`.

**Hardware:**
- `disk-min-80gb`, `ram-min-8gb`, `cpu-min-4-cores`.

**Provider plane:**
- `provider-snapshot-scheduled`: panel screenshot or API call proves daily snapshot, ≥30d retention.
- `provider-region-jurisdiction`: DC city + jurisdiction string recorded in the provisioning report (refuse acceptance until filled — region naming ambiguity risk).

### External smoke probes (from off-host)

- `external-no-service-http`: `curl http://${VPS_IP}/` returns connection-refused (port 80 open in UFW but nothing listening yet — correct pre-S-046).
- `external-no-service-https`: same for 443.
- `internal-ports-not-public`: `nmap -Pn -p 5432,5433,6379,8080,8443,1025,8025,9000,9090,3000 ${VPS_IP}` shows all closed/filtered.
- `only-expected-ports-public`: `nmap -Pn -p- ${VPS_IP}` shows only `{22, 80, 443}` open. **Anything else open is a fail.**

### Lightweight DR drill (full drill is S-043)

- `manual-snapshot-creates`: trigger snapshot via panel/API; assert it appears.
- `snapshot-restore-on-throwaway`: spin up a separate test VPS from the snapshot (never restore over prod); confirm `${OPS_USER}` + `/etc/timezone` + `ufw` survived; record restore time. **Destroy the throwaway after.**

### Availability + latency sanity

- `ping-loss-under-1pct`: `ping -c 100 ${VPS_IP}` < 1% loss.
- `zurich-latency-under-30ms`: `mtr --report --report-cycles 50 ${VPS_HOST}` from a Swiss ISP shows < 30 ms last-mile.

### Bootstrap-script dry-run (the load-bearing artifact)

- `bootstrap-idempotent-throwaway`: run `provision-vps.sh` against a throwaway VPS; full log captured; **re-run; assert second run is no-op** (no destructive changes). Catches the classic "works first time, eats config second time" bug.
- `bootstrap-from-clean-image`: assert script works against the exact OS image string used in prod (record image ID).

### Test data + fixtures

- ops SSH pubkey (operator-owned; not in repo).
- Provider API token (scoped, operator-owned; not in repo).
- `${VPS_HOST}` / `${VPS_IP}` / `${OPS_USER}` / `${VPS_HOSTNAME}` — parameterized; read-only probes.
- Throwaway VPS — operator spins up; explicit teardown step asserted in the report.

### Coverage gaps (deferred)

- Full DB + app restore drill → S-043.
- HTTPS termination + cert provisioning → S-046 + S-117.
- Real SLO measurement (99% over 30 days) → S-037 + S-108 + S-111.
- DPA / data-residency contractual audit → manual UAT.
- Multi-tenant load smoke → S-046+ perf stories.

### Risks

- **Region naming ambiguity** ("Switzerland" sometimes = Zurich, sometimes = Falkenstein-near-CH): record literal DC city + jurisdiction; refuse acceptance until field is filled.
- **Snapshot retention default may be < 30 days** (some providers default to 7): assert ≥30 before sign-off, not after.
- **PTR record gating differs by provider** (Hetzner/Exoscale/Infomaniak panel; OVH ticket): blocking checklist item.
- **IPv4 surcharge** (Hetzner): record monthly cost in TCO line.
- **Bootstrap-script non-idempotency** — most likely DR-day biter. Mitigated by `bootstrap-idempotent-throwaway` probe.
- **Probe-from-VPS false negatives** (`nmap` on host sees `localhost`): external probes MUST run from off-host (operator laptop or a second VPS); bake into verify-script preamble.
- **Throwaway VPS billing leak** from the snapshot-restore drill: explicit teardown step + "destroyed at HH:MM" line in report.

## Performance plan

### Capacity sizing
- backend ~512 MB idle / ~1 GB peak
- Postgres 17 ~256 MB / ~1 GB
- Keycloak 26.5 prod ~768 MB
- Loki + Prometheus + Grafana ~640 MB
- GlitchTip + its Postgres ~768 MB
- Caddy/Traefik ~64 MB
- **Total idle ~3 GB / peak ~5 GB; 8 GB VPS = ~3 GB headroom over peak. Right-sized day-1.**
- Bump to 16 GB only if operator wants in-box backup-restore-into-staging or parallel test-restore.

### Disk
- 80 GB comfortable (Postgres < 1 GB initial, slow growth; Loki 7-14d retention < 10 GB; images < 5 GB).
- Bump to 160 GB at S-046 inflection or if Loki retention extends.

### Network
- 1 Gbps uplink at all candidates; nowhere near saturation at 12-club scale.
- Egress caps: Hetzner 20 TB/mo soft (€1/TB over); Exoscale unmetered; Infomaniak metered. Verify before commit.

### Latency budget (CH client → CH/EU VPS)
- RTT: p95 < 30 ms (target p50 < 15 ms; CH-Swiss VPS typically < 15 ms).
- TLS handshake cold: < 60 ms; warm session resumption: < 10 ms.
- App: Spring Boot p95 read < 500 ms (NFR — owned by app stories).
- Total page-load contribution from host + network: ~50-100 ms (well within 3s p95 budget).

### Performance test plan
- **Provisioning latency:** `time provision-vps.sh` end-to-end; p95 ≤ 5 min over 3 fresh-VPS runs.
- **Network RTT:** `ping -c 100`; p50, p95, p99. Pass: p95 < 30 ms, p99 < 50 ms.
- **TLS handshake** (post-S-041): `curl -w '%{time_appconnect}\n'` cold (10 runs) + warm (100 runs with session resumption). Pass: cold p95 < 80 ms, warm p95 < 15 ms.
- **Disk IO:** `fio` 4k random read + random write, iodepth=32, runtime=60s. Pass: NVMe ~10k IOPS, p99 < 1 ms; SATA-SSD fallback ~3k IOPS, p99 < 5 ms (downgrade flag).
- **CPU baseline:** `sysbench cpu --threads=1 run` and `--threads=4 run`; record events/sec.
- **Memory bandwidth:** `sysbench memory --memory-block-size=1M --memory-total-size=10G run`; record MB/s.
- **Steady-state compose footprint:** 24h `docker stats --no-stream` sampled every 60s after compose up (no traffic). Pass: total RSS ≤ 3.5 GB idle; no monotonic growth (memory-leak smell).
- **Synthetic-load compose footprint:** 30-min k6 ramp matching S-108 baseline mix; record peak RSS. Pass: ≤ 5.5 GB peak (confirms 8 GB headroom thesis); fail-loud if any container OOM-kills.
- **Baseline persistence:** all results land in `next/ops/vps-perf-baseline.md` (provider + plan + region + date stamped); feeds S-108 and S-111.

### CPU sizing
- 4 vCPU sufficient at our scale; Postgres + Spring Boot don't peg under typical load.
- K8s migration (S-046) might push toward 2 nodes; defer.

## Open design questions

1. **CH-strict vs. EU-acceptable residency.** Both meet C4. Operator: any club's legal review insisted on CH-borders-only? Default Hetzner DE if no flag.
2. **DNS provider — Cloudflare vs. DNSimple.** Both decouple from VPS. Cloudflare free + optional DDoS proxy; DNSimple paid + Swiss-friendly + cleaner API. Operator preference.
3. **Co-locate Keycloak in production on this VPS, or separate-host IdP per S-116?** Affects RAM sizing (8 GB tight if Keycloak + observability + Postgres + backend all co-resident).
4. **Object-storage provider for S-042: pre-decide here** (Exoscale SOS / Infomaniak Swiss Backup / Hetzner Storage Box) so region pairing is clean? Or defer to S-042?
5. **Staging VPS for S-113 rehearsal-2:** scoped here, or stood up only for rehearsal then destroyed? Affects cost.
6. **At-rest encryption:** LUKS on data volume OR provider attestation only? Affects ops complexity (LUKS passphrase at boot via console).

<!-- modernize-refine: end -->

## Notes
Decision can happen close to cutover — earlier provisioning means paying for an unused VPS. But the provider choice is a story so it doesn't fall between the cracks. C6 (≤6 hr cutover) implies the VPS must be provisioned and warm **before** cutover night, not during — recommend provisioning ~1 week before rehearsal-2 (S-113).
