---
id: S-037
title: External uptime probe (independent of VPS)
epic: E-04
status: todo
depends_on: []
acceptance:
  - Probe runs *outside* the production VPS — different provider OR different region (recorded in drill evidence). Self-hosted Uptime Kuma v2 on a small separate VPS preferred for EU/CH residency; SaaS fallback (BetterUptime EU free tier) acceptable with documented residency tradeoff.
  - Monitors configured: backend liveness (`/actuator/health/liveness`), backend readiness (`/actuator/health/readiness`), Keycloak realm (`/realms/fls/.well-known/openid-configuration`), HTTPS cert expiry (30-day warning), domain expiry (30-day warning), DNS A-record validation.
  - Health monitors at 60s interval; cert + domain monitors daily.
  - Alert fires on 2 consecutive failures with auto-recovery notification; no re-alert during an ongoing incident.
  - **Two independent alert channels** — primary non-email (Pushover, Telegram, ntfy, or Slack); secondary email via **external SMTP NOT the in-VPS mailpit / S-091 relay** (must survive a prod-VPS outage).
  - Probe-host hardening matches S-044 baseline (SSH key only, UFW, fail2ban, unattended-upgrades).
  - Uptime Kuma UI auth enabled; default admin password rotated; UI bound to loopback + Tailscale/WireGuard access OR reverse-proxied with basic auth.
  - **Drill executed and captured:** power off prod VPS for ≥2 min; alert received within 3 min on primary channel; auto-recovery notification on power-on. Evidence in `docs/modernization/ops/S-037-probe-drill-evidence.md` (screenshots of Kuma timeline, both channel alerts, provider panel timestamps).
  - Synthetic smokes pass: cert-expiry simulation (badssl.com), probe-restart resilience, probe-host reboot auto-start, alert-channel self-test.
  - Re-drill cadence pinned to quarterly + after any change to Caddy routing / Actuator paths / Keycloak realm name / DNS.
estimate: S
adr_refs: [0011]
parity_test: none
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
refined_speculative: true
refined_speculative_at: 2026-05-15
---

## Context
The "observability stack dies with the host" failure mode is real (ADR 0011). When the production VPS goes down, the in-VPS Grafana + Loki + Prometheus + alertmanager all die with it — the alerts they would have fired go silent. **This story is the safety net:** an externally-hosted probe that watches the production stack from outside and alerts via a channel that bypasses the in-VPS mail/notification path.

The whole architecture is **two independent loops** — in-VPS alerts catch app-side degradation while the host is up; the external probe catches "host is dead" outages. Each loop must work without the other.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Pick the host shape (self-hosted Uptime Kuma on a separate small VPS — recommended — OR BetterUptime EU SaaS free tier).
- [ ] If self-hosted: provision the probe VPS (Hetzner CX11 ~€4/mo, different region from prod e.g. FI if prod is DE); apply `next/ops/uptime-probe/probe-host-bootstrap.sh` adapted from `provision-vps.sh` (S-044).
- [ ] Author `next/ops/uptime-probe/docker-compose.yml` (Uptime Kuma v2; named volume `kuma-data`; restart=unless-stopped; UI bound to loopback + Tailscale OR exposed via Caddy + basic auth on a non-default port).
- [ ] Author `next/ops/uptime-probe/monitors.md` — declarative source-of-truth for the 6 monitors (Kuma's UI matches).
- [ ] Author `next/ops/uptime-probe/alert-channels.md` — channel inventory + credential vault refs.
- [ ] Author `next/ops/uptime-probe/drill-procedure.md` — step-by-step drill + evidence template.
- [ ] Author `next/ops/uptime-probe/README.md` — purpose, topology, how to add a monitor, how to drill, how to rebuild.
- [ ] Configure monitors + alert channels in Kuma; rotate default admin password; enable 2FA if available.
- [ ] Run SYN-4 (alert-channel self-test) FIRST — gates the drill (so the drill measures detection, not misconfigured alerting).
- [ ] Run SYN-1 (cert-expiry simulation via badssl.com) + SYN-2 (probe restart) + SYN-3 (probe-host reboot) in parallel with checklist sign-off.
- [ ] **Run the drill:** power off prod VPS; record T_detect / T_alert_primary / T_alert_secondary / T_recover; commit evidence.
- [ ] Re-drill quarterly; after any change to Caddy routing / Actuator paths / Keycloak realm name / DNS.

<!-- modernize-refine: start -->

## Design notes

### Module layout

| Path | Purpose |
|---|---|
| `next/ops/uptime-probe/docker-compose.yml` | Single-service compose: `louislam/uptime-kuma:2`; named volume `kuma-data`; restart=unless-stopped; UI binding strategy (loopback + tailscale OR Caddy + basic auth). |
| `next/ops/uptime-probe/probe-host-bootstrap.sh` | Adapted from S-044's `provision-vps.sh`. SSH key only, UFW (22 + 3001 if no Tailscale), fail2ban, unattended-upgrades, Docker + compose plugin, optional Tailscale, clone of `next/ops/uptime-probe/` + `docker compose up -d`. |
| `next/ops/uptime-probe/monitors.md` | Declarative source-of-truth: 6 monitors (see Monitors). |
| `next/ops/uptime-probe/alert-channels.md` | Channel inventory + vault refs (no secrets in repo). |
| `next/ops/uptime-probe/drill-procedure.md` | Drill steps + evidence template. |
| `next/ops/uptime-probe/README.md` | Operator manual. |
| `docs/modernization/ops/S-037-probe-drill-evidence.md` | Drill log + screenshots; committed once first drill passes; append-only across re-drills. |

**No code in `next/server/` or `next/web/`.** Probe is entirely external infrastructure.

If SaaS option chosen (BetterUptime), drop `docker-compose.yml` and `probe-host-bootstrap.sh`; the rest of the files describe the SaaS account configuration as code-adjacent docs.

### Monitors (committed as declarative source)

```yaml
monitors:
  - name: backend-liveness
    type: http
    url: https://fls.example/actuator/health/liveness
    method: GET
    expected_status: 200
    expected_body_keyword: '"status":"UP"'
    interval_seconds: 60
    retries: 2
    channels: [pushover-primary, email-secondary]

  - name: backend-readiness
    type: http
    url: https://fls.example/actuator/health/readiness
    interval_seconds: 60
    retries: 2
    channels: [pushover-primary, email-secondary]

  - name: keycloak-realm
    type: http
    url: https://fls.example/auth/realms/fls/.well-known/openid-configuration
    interval_seconds: 60
    retries: 2
    channels: [pushover-primary, email-secondary]

  - name: tls-cert-fls-example
    type: cert
    hostname: fls.example
    port: 443
    warn_days_before_expiry: 30
    interval_seconds: 86400
    channels: [email-secondary]

  - name: domain-expiry-fls-example
    type: whois
    domain: fls.example
    warn_days_before_expiry: 30
    interval_seconds: 86400
    channels: [email-secondary]

  - name: dns-a-record-fls-example
    type: dns
    hostname: fls.example
    record_type: A
    expected_values: ["<prod-or-Caddy-ip>"]
    interval_seconds: 300
    retries: 2
    channels: [pushover-primary, email-secondary]

# Deliberately omitted:
# - Direct Postgres TCP probe — backend readiness covers DB indirectly; no point exposing 5432.
# - In-VPS Grafana / Prometheus UI — dies with the host; correlated-noise only.
```

### Alert channels (shape, no secrets)

```yaml
channels:
  - id: pushover-primary
    type: pushover
    purpose: "primary mobile-push; €5 one-time, EU server option"
    credentials_ref: "vault://ops/uptime-probe/pushover"
  - id: email-secondary
    type: smtp
    purpose: "redundancy; MUST NOT be in-VPS mailpit OR S-091 relay"
    smtp_host: "smtp.<external-transactional-eu>"   # e.g. Resend EU, Mailgun EU
    credentials_ref: "vault://ops/uptime-probe/smtp"

flapping_policy:
  retries_before_alert: 2
  resend_during_incident: false   # one alert per incident
  notify_on_recovery: true
```

**Anti-pattern explicitly forbidden:** ONLY email via in-VPS SMTP — when the VPS dies, mail dies too. Two channels via independent paths are mandatory.

### Probe-host topology

- Self-hosted Uptime Kuma on a separate small VPS (Hetzner CX11 ~€4/mo in a *different region* from production — e.g. prod DE, probe FI; ideally different provider).
- Different region/provider avoids correlated-failure with the production VPS (DC-level outage, provider-wide incident).
- Probe host doesn't store user PII (only uptime history); blast-radius is limited; backups not required (data is replaceable from `monitors.md` in git).
- UI access strategy (operator's call):
  - **A (recommended):** UI bound to `127.0.0.1:3001`; access via Tailscale/WireGuard tailnet (zero public exposure of 3001).
  - **B (alternative):** UI exposed via Caddy on `https://probe.fls.example` with basic-auth + Uptime Kuma's built-in admin auth.

### Drill procedure (committed)

Pass criterion (matches AC): alert received within 3 min of VPS power-off.

```
1.  Open provider control panel for production VPS.
2.  Open mobile device with Pushover + email client visible.
3.  Open docs/modernization/ops/S-037-probe-drill-evidence.md; start a new
    section with `## Drill log YYYY-MM-DD HH:MM`.
4.  T0   = wall-clock now. Start stopwatch.
5.  T0:    "Stop / Power off" prod VPS (hard stop, not graceful).
6.  Observe Kuma dashboard + alert channels.
7.  T+~60s-120s: Kuma marks 1st failure (60s interval + jitter).
8.  T+~120s-180s: Kuma marks 2nd failure → fires alert.
9.  Record T_detect = first monitor turns red in Kuma.
10. Record T_alert_primary = Pushover notification received.
11. Record T_alert_secondary = email received.
12. Power prod VPS back on via control panel.
13. Wait for all monitors to return green.
14. Record T_recover_alert = recovery notification received.
15. Capture screenshots: Kuma timeline, primary + secondary alert
    notifications (outage + recovery), provider panel power-off → power-on
    timestamps.
16. Attach to the evidence file. Compute T_alert_primary - T0; pass if ≤ 3 min.
17. If fail: file remediation tasks (story does NOT close); re-run.
```

**Re-drill cadence:** quarterly at minimum, AND after any change to Caddy routing / Actuator paths / Keycloak realm name / DNS.

### Integration with other stories

| Other story | Relationship |
|---|---|
| S-031 structured JSON logging | Probe is intentionally external; does NOT ship logs into Loki. |
| S-032/033/034 in-VPS observability stack | Complement, do NOT replace. Two independent loops by design. |
| S-035 (Grafana dashboards) / S-036 (alert rules) | In-VPS alerts catch app-side degradation while host is up; S-037 catches "host is dead". |
| S-041 (reverse proxy) | Public HTTPS endpoints + Let's Encrypt cert lifecycle; probe verifies both. **Coordination needed:** add `/healthz/liveness` + `/healthz/readiness` → `backend:8081/actuator/health/*` to S-041 routes, OR expose `/actuator/health/*` on the app port 8080. |
| S-044 (VPS provider) | Provides `provision-vps.sh` baseline cloned into `probe-host-bootstrap.sh`. Probe MUST be a second VPS (different region OR different provider). |
| S-091 (production SMTP) | Probe's secondary email channel must NOT use this relay. Use a separate transactional service (Resend EU, Mailgun EU). |
| S-001 / S-030 (Actuator) | Probe consumes `/actuator/health/liveness` + `/readiness`. **Coordination:** Spring Boot readiness composite is currently `ping + diskSpace` only — without `db` + `JWKS` contributors, probe stays green even when downstream dies. S-030 owns the contributor expansion; flag as a soft dependency. |
| S-108 / S-111 (perf baseline + verification) | Consume probe history for monthly SLO % computation. S-037 produces the data; downstream stories analyze. |

### Alternatives considered

- **Option A (chosen): Self-hosted Uptime Kuma v2 on a separate small VPS, different region from prod, ideally different provider.** Full EU/CH residency for probe metadata (C4), ~€4/mo, single-container ops burden, 60s probe interval comfortably meets the 3-min alert SLO.
- **Option B (fallback): BetterUptime SaaS EU free tier.** Set-and-forget; EU-hosted; mobile app + SMS. Rejected as primary because (1) 3-min minimum interval on free tier eats most of the 3-min alert budget; (2) vendor lock-in; (3) free tier's single notification channel violates redundancy. Retained as documented fallback if operator does not want a second VPS.
- **Option C (rejected): BetterStack paid (~€25/mo).** Tighter intervals + hosted status page; overkill day-1.
- **Option D (rejected): Healthchecks.io push-mode.** Wrong shape — requires the production app to push heartbeats, fails the "host is dead" case.
- **Option E (rejected): Co-locate probe on production VPS.** Defeats the entire purpose.
- **Option F (rejected): AWS CloudWatch Synthetics / GCP Cloud Monitoring.** US-region metadata violates C4 + vendor lock-in.
- **Option G (rejected): UptimeRobot free tier.** US-based; metadata leaves EU (operator's call but recommend against).

## Edge cases & hidden requirements

- **Probe co-located with VPS:** AC strengthened to "different host AND different provider OR different region", recorded in drill evidence — separation is the verifiable gate.
- **Alert channel co-located with VPS:** explicitly forbidden in-VPS Mailpit, in-VPS Grafana contact-point, S-091 prod SMTP relay, and any DNS resolution that resolves only at the prod VPS.
- **Probe URL Spring Security:** S-020 already permits `/actuator/health/**` unauthenticated; no auth-negotiation needed for the probe.
- **Probe URL `show-details`:** S-001 pins `show-details: when_authorized` — unauth probe sees `{"status":"UP"}` only, no info leak.
- **Probe URL on management port 8081:** S-039 binds backend health on `localhost:8081`; not public. **Coordination needed (S-041):** add `/healthz/liveness` + `/healthz/readiness` routes → `backend:8081/actuator/health/*`, OR expose `/actuator/health/*` on app port 8080.
- **Cert-expiry blind spot:** Let's Encrypt auto-renewal via Caddy; if it silently fails, operator has no signal without the cert-expiry monitor — included.
- **DNS / domain expiry:** unrelated to VPS health, same safety-net category — included.
- **Probe on shallow URL = false negative:** `/actuator/health/readiness` returns UP if Spring booted, even with Postgres/Keycloak dead. S-001's readiness composite is `ping + diskSpace` only — needs `db` + JWKS contributors to be meaningful. **Flag for S-030**; accept shallow probe day-1, file follow-up.
- **Probe interval vs. 2-failure rule:** 60s × 2 = ~120s detection; push dispatch < 30s; AC's 3-min budget is comfortable. If drill consistently exceeds 3 min, consider 30s interval to make the budget deterministic.
- **Flapping during deploys:** every backend redeploy = ~10-30s readiness gap. 2-consecutive-fail at 60s = potential false alert. Document maintenance-window procedure in `drill-procedure.md`: pause monitors before deploy, resume after; OR use Kuma's "Maintenance" feature.
- **Drill evidence:** AC requires drill captured at `docs/modernization/ops/S-037-probe-drill-evidence.md`; not just "we did it once" — append-only across re-drills.
- **Probe IP allowlisting:** if S-041 / future WAF restricts ingress by IP, SaaS probe source IPs must be allowlisted. UptimeRobot + BetterUptime publish rotating IP lists. Flag for when WAF lands.
- **Probe self-monitoring:** if the probe host itself dies, who tells the operator? SaaS handles via vendor uptime. Self-hosted has no auto-watcher — accept residual risk mitigated by VC-6 dual channels (one of which is provider-hosted).
- **Multi-region false-positives:** single-region probe + transient network partition = false alert. AC doesn't require multi-region; flag as upgrade path.
- **Probe metadata residency (C4):** uptime history + latency timestamps don't contain user PII, but reveal operator telemetry. Recommend EU/CH (self-hosted or BetterUptime EU); document the conscious choice if US SaaS picked.
- **TLS cert mismatch / SNI mismatch:** probe hitting raw IP (pre-S-041) won't catch TLS misconfigurations; cert monitor closes the gap.
- **Default readiness contributor set:** flag for S-030 — needs `db` + JWKS for the probe to be truly meaningful.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-037 |
|---|---|---|
| Probe credentials in config files | Medium | Probe-host volume encrypted; secrets never committed; vault refs only in repo. |
| `/actuator/health` info disclosure | High | `show-details: when_authorized` on public path (S-001 / S-030); probe targets `liveness` + `readiness` only. |
| Probe host as attack surface | Medium | Same hardening as prod (SSH key only, UFW, fail2ban, unattended-upgrades); no shared SSH keys with prod. |
| Uptime Kuma UI exposed on 3001 | Medium | Bind to loopback + Tailscale access OR Caddy + basic-auth OR non-default port + built-in auth. |
| Alert channel webhook leakage | Medium | Stored in Kuma config only; rotate on suspected exposure. |
| Probe metadata residency (C4) | Low | Self-hosted EU/CH preferred; BetterUptime EU as SaaS fallback. |
| SMTP abuse on probe host | Medium | No inbound SMTP; outbound-only via SaaS sender. |
| DDoS the probe to silence alerts | Low | SaaS absorbs by infra; self-hosted has low target value at $3/mo. |
| Probe-host compromise → prod pivot | High if mishandled | No shared SSH keys / admin accounts; probe only knows public health URLs. |
| TLS validation disabled by misconfig | Medium | Cert validation + expiry monitor on. |

### Authorization

N/A — no app-side endpoints introduced. Uptime Kuma UI gated by built-in auth (admin password rotated; 2FA if version supports).

### Input validation

N/A — probe emits outbound GETs to fixed health URLs; parses status code + body keyword only.

### PII handling

- **Uptime history (timestamps, latency, status):** operator telemetry, classification non-PII operator data; retained on probe host; not subject to GDPR/FADP data-subject rights.
- **Alert message payloads:** may contain target URL/hostname; classification operator data; no member data traverses this path.
- **Probe-host volume contents** (alert secrets): sensitive operator secret; redact from any exported logs or screenshots; never commit to repo.

### Audit-log events

N/A app-side. Uptime Kuma's internal event log (alert fired, recovered, acknowledged-by) retained ≥90 days per ADR 0011 for incident review.

### Cross-tenant leakage

N/A — single-tenant operator infrastructure.

### OWASP applicability

- **A01 Broken Access Control:** Kuma UI auth enabled; default password changed; UI not publicly exposed without auth.
- **A02 Cryptographic Failures:** HTTPS-only probe targets; TLS validation on; cert-expiry monitor.
- **A04 Insecure Design:** ≥2 independent alert channels (Pushover primary + email secondary).
- **A05 Misconfiguration:** Actuator `show-details=never` on public path (S-030); probe-host UFW default-deny.
- **A06 Vulnerable Components:** Kuma image pinned, Renovate bumps; unattended-upgrades on probe-host OS.
- **A07 Auth Failures:** strong password on Kuma admin; 2FA if available.
- **A08 Integrity Failures:** Kuma image pinned by digest, not floating tag.
- **A09 Logging Failures:** alert log retention ≥90 days; channels tested quarterly.
- **A10 SSRF:** low — probe is outbound HTTP client; lock target list to static config (no user-supplied URLs).

### Hard security ACs

- Probe host hardened identically to prod (SSH key only, UFW, fail2ban, unattended-upgrades).
- Kuma UI auth enabled; default password changed; strong password; 2FA if supported.
- Probe metadata residency EU/CH (self-hosted or EU SaaS); document conscious choice if US SaaS.
- ≥2 independent alert channels (primary non-email + secondary email via external SMTP — NOT in-VPS path).
- Alert channel webhooks stored in probe-host config only; not committed.

## Test plan

### Pyramid
- Unit / Integration / E2E: 0 (infra; no app code).
- Parity: 0 — `parity_test: none`; legacy stack had no external probe.
- Acceptance = verification checklist + live drill + 4 synthetic smokes.

### Verification checklist (pre-drill gate)

Each item ticked in evidence file before drill is run:

- **VC-1 Probe-host independence:** different provider OR different region; record both in evidence.
- **VC-2 Uptime Kuma reachable:** UI returns; auth required; default admin password rotated.
- **VC-3 Monitor coverage:** 6 monitors (liveness, readiness, Keycloak realm, cert expiry, domain expiry, DNS A).
- **VC-4 Probe interval:** health monitors at 60s; cert+domain daily.
- **VC-5 Failure threshold:** 2 consecutive failures before alert.
- **VC-6 Two alert channels:** primary non-email + secondary email via external SMTP. Record provider names (not secrets) in evidence.
- **VC-7 Cert-expiry threshold = 30 days.**
- **VC-8 Domain-expiry threshold = 30 days.**
- **VC-9 Probe-host hardening matches S-044 baseline:** show `ufw status`, `sshd -T | grep -i password`, `systemctl is-active fail2ban` in evidence.
- **VC-10 Kuma UI auth enabled:** default password rotated; 2FA if available; not exposed on plain HTTP.
- **VC-11 Auto-start:** `restart: unless-stopped` on container; `unattended-upgrades` active on host.

### The drill (acceptance event)

(See Design notes §"Drill procedure".)

Pass criteria (all must hold):
- `T_alert_primary - T0 ≤ 3 min`.
- At least one alert per **both** channels received during outage.
- Recovery notification received on ≥ primary channel.

Stopwatch tolerance: ±15s on the 3-min budget; re-run once if between 2:45 and 3:15 before declaring pass/fail.

### Synthetic smokes (required for sign-off)

- **SYN-1 Cert-expiry simulation:** throwaway TLS monitor against `https://expired.badssl.com/`; confirm Kuma flags within one cycle. Delete the monitor after capturing screenshot.
- **SYN-2 Probe-restart resilience:** `docker compose restart`; confirm UI back within 60s, history persists, no monitors reset to Unknown.
- **SYN-3 Probe-host reboot auto-start:** `sudo reboot`; confirm Kuma starts on boot; capture `systemctl status` or `docker ps`.
- **SYN-4 Alert-channel self-test:** Kuma's "Test notification" button against each channel; capture both receipts. **Run SYN-4 FIRST** so the drill measures detection, not misconfigured alerting.

### Test data + fixtures

- Production VPS hostname / URL: from S-044.
- Alert channel tokens / SMTP creds: operator-side vault (1Password / pass / Bitwarden); never committed.
- Throwaway TLS target (`expired.badssl.com`): public; delete monitor after SYN-1.
- Drill evidence file: append-only at `docs/modernization/ops/S-037-probe-drill-evidence.md`.

### Coverage gaps (deferred)

- SLO measurement from probe history (monthly availability %): blocked on S-108 / S-111.
- Alert escalation / on-call rotation: solo operator; out of automation scope.
- Public status page: out of scope.
- Probe-host-of-probe-host (turtles): not automated; mitigated by dual independent channels.
- Drill on real cert-expiry event: cannot trigger on demand; SYN-1 with badssl is the standing proxy.

### Risks

- **Probe colocated with prod:** drill catches (power-off prod → probe dies → no alert → fail). VC-1 + drill = mitigation.
- **Mail-only alerts when VPS SMTP dies:** VC-6 mandates non-email primary + external SMTP secondary.
- **Unpatched probe host:** VC-11 requires unattended-upgrades; quarterly manual check noted.
- **Alert flapping during sustained outage:** Kuma's resend interval default 0 (no resend) acceptable for solo operator.
- **Drill not run (paper compliance):** evidence file is the gate; story does NOT close without a dated drill log meeting pass criteria — make this the PR-merge check.
- **Stopwatch imprecision:** ±15s tolerance; re-run once if borderline.
- **Synthetic monitors leaking into prod dashboards** (SYN-1 forgotten): explicit "delete throwaway monitors" step in SYN-1.

## Performance plan

### Hot paths
N/A — no new app endpoints. Probe targets pre-existing `/actuator/health/{liveness,readiness}` + Keycloak `.well-known` + cert + DNS.

### Latency budget
- `/actuator/health/liveness` probed response: p95 < 50ms (in-memory `LivenessStateHealthIndicator`).
- `/actuator/health/readiness` probed response: p95 < 200ms (one DB ping when `db` contributor lands per S-030).
- **Alert latency end-to-end: ≤ 180s from incident to operator notification.** Breakdown: 2 × 60s consecutive-failure (120s) + ≤30s push dispatch (Pushover/Telegram/Slack) + buffer. Email excluded from this budget (relay variance defeats 3-min target — secondary only).
- **Probe overhead on prod: ≤ 5 req/min total ingress** (3 hot monitors at 60s + 1 daily cert + 1 daily domain + 1 5-min DNS). Negligible; must not appear in p99 of any app endpoint.

### Probe-host capacity
- **RSS budget: ≤ 256 MB total** for compose stack (Kuma idle ~64 MB + overhead). Alert if RSS > 256 MB sustained (history-retention misconfig or runaway monitor count).
- **Disk budget: ≤ 1 GB** for SQLite history + logs. Configure Kuma `KEEP_DATA_PERIOD_DAYS=90` (down from default 180) if disk pressure observed.
- **Sizing:** Hetzner CX11 (2 GB / 40 GB) at €4/mo → 8x headroom on RAM, 40x on disk. No vertical scaling expected over 5 years.
- **Egress:** ~5 HTTPS req/min × ~1 KB = ~7 MB/day outbound; well under any cap.

### N+1 / indexes / caching
N/A. Do NOT cache `/actuator/health` responses — caching defeats the purpose.

### Performance test plan
- **Probe-load measurement on production:** Spring Boot `http.server.requests` metric filtered by URI `/actuator/health/**` for 1h window post-deployment. Pass: < 5 req/min total; p95 < 200ms; zero 5xx.
- **Alert-latency drill** (matches AC): stop prod backend; stopwatch from stop to phone notification; pass ≤ 180s for push channels. Repeat 3× for variance.
- **Probe-host resource measurement:** `docker stats --no-stream` sampled every 5 min for 24h post-compose-up; append to CSV. Pass: Kuma container RSS < 128 MB sustained, CPU < 5% sustained, stack RSS < 256 MB.
- **False-positive rate (operational):** count alerts over 30 days post go-live; classify true-positive / flaky-network / config-error. Pass: < 1 false-positive/week after week 2. If higher, raise consecutive-failure threshold from 2 → 3 (detection ~180s; only if push dispatch < 30s, else exceeds 3-min budget).

<!-- modernize-refine: end -->

## Notes
Cheapest is a free-tier SaaS (BetterUptime EU free; UptimeRobot US) — but residency (C4) and metadata residency tradeoffs favor a self-hosted Uptime Kuma on a small separate VPS in a different region from prod. ~€4/mo is the small price for full residency + dual-channel reliability.
