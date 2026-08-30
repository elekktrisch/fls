# Privacy notice — the personal data that AlpenFlight keeps

**Status.** This document is the source text of the privacy notice. The repository holds it. The
product does not publish it yet. The operator decides where the product publishes it, and in which
languages.

**Rule.** Each statement below names the code that makes it true.
`PrivacyNoticeMatchesTheShippedBehaviourTest` reds when this notice and the code disagree.

## 1 — The client IP of an anonymous public registration

### What the application records

A person registers for a discovery flight or a scenic flight. The person has no account, and the
application does not authenticate the person. The application records the IP address of that person
on the audit row of the registration.

Two endpoints record an IP address:

- `POST /api/v1/public/clubs/{clubSlug}/discovery-flight-registrations`
- `POST /api/v1/public/clubs/{clubSlug}/scenic-flight-registrations`

`PublicRegistrationController.java:78` and `PublicRegistrationController.java:104` declare them.
`PublicRegistrationTxWriter.java:100` writes the audit row. `ClientIpResolver.java:21` resolves the
address from the peer address, or from the last public hop of `X-Forwarded-For` when a proxy is the
peer.

The audit row carries `actor_kind = ANONYMOUS_PUBLIC` and `system_actor = false`. The application
records an IP address on no other write. `MutationAuditEvent.Builder` refuses a client IP on every
other actor kind, and `AuditTrailService.recordAnonymousPublicSubmission` drops the address when the
submitter holds a bearer token.

The audit row always names the club of the registration. `MutationAuditEvent.Builder` refuses a
client IP on a row that names no club, because both the retention job and the erasure endpoint reach
a row through its club. When the application resolves no club, it writes the audit row and drops the
address.

### Why the application records it

The two endpoints accept a write from the internet without authentication. An abuse guard limits how
many registrations one source sends (`PublicRegistrationAbuseGuard.java:22`). The guard holds its
counters in memory for 15 minutes, and it writes no address to the database. The audit row is the
only place that keeps the address. Without the address, the audit trail cannot name the source of an
abusive registration.

The purpose is the investigation of abuse. No API endpoint returns the address:
`AuditEventDtos.AuditEventRow` carries no client IP, so the audit-trail screen never shows one.

### How long the application keeps it

The retention window is 90 days.

To redact means this: the application replaces the address with nothing, and the application keeps
the audit row. The trail survives. The personal data goes.

The retention job `client-ip-retention` runs every day. The job redacts every client IP that is 90
days old or older, so the application keeps an address for less than 90 days.
`MutationAuditEvent.clientIpRetentionHasElapsedAt` holds the rule, and `ClientIpRetentionWindowTest`
proves the boundary. The job runs once for each club, and it includes a club that an operator
deleted (`ClientIpRetentionJob.java:49`). The job reads no row of another club, because it works
inside `Tenants.runAs`.

The job then runs one more sweep, for the audit rows that name no club
(`ClientIpRetentionJob.java:60`). The application writes no address on such a row, so that sweep
finds only what a database operation outside the application wrote. The sweep makes the window hold
for those rows too. `EveryClientIpStaysReachableByTheRetentionSweepIT` proves that the table keeps
no address past the window, whatever wrote the row.

### How a person gets the address removed earlier

A club administrator or a system administrator redacts one address before the window elapses:

`DELETE /api/v1/admin/audit-events/{auditEventId}/client-ip`

The endpoint answers 204 and keeps the audit row (`AuditAdminController.java:65`). The endpoint
answers 404 when the club of the caller holds no audit event with that id, so an administrator of
one club cannot reach the row of another club.

The application records the erasure itself.
`ClientIpRedaction.redactOneClientIpAheadOfTheRetentionWindow` writes a `CLIENT_IP_REDACTED` audit
row, and that row carries no IP address.

### What the application cannot do to an audit row

The database refuses every UPDATE on `t_mutation_audit_event`, with one exception:
`V60__mutation_audit_event_client_ip_redactable.sql` grants the application an UPDATE on the
`client_ip` column alone. The database refuses every DELETE
(`V54__split_app_role_append_only_audit.sql:27`). So the application redacts an address, and the
application never deletes an audit row.

## 2 — The licence number and the medical dates in the audit trail

### What the application records

A member edits their own licences and medical dates through `PATCH /api/v1/me/person/licences`
(`MePersonLicencesController.java:53`). The application records the value before the edit and the
value after the edit, in `t_mutation_audit_event.before_state` and `after_state`.

### The fields that stay verbatim

- `licenceNumber`
- `medicalClass1ExpireDate`
- `medicalClass2ExpireDate`
- `medicalLaplExpireDate`
- `gliderInstructorLicenceExpireDate`
- `motorInstructorLicenceExpireDate`
- `partMLicenceExpireDate`

`application.yml` holds the list at `audit.redaction.entities.PersonLicences.allow`.
`MePersonLicencesControllerIT.java:133` pins that a medical date lands un-redacted. The remaining
allow-listed entries are yes-no flags, and a flag names no value.

The `Person` entity carries the opposite policy. `application.yml` puts `Person` on
`audit.redaction.deny-all`, so every field of a person renders `[redacted]` in the audit trail.
`PersonLicences` is the deliberate exception.

### Why the application keeps them

Safety of flight depends on a current licence and a current medical certificate. A club must answer
two questions after an incident: which value was in effect on the day, and who changed it. The
operator decided on 2026-08-20 that this is a legitimate audit purpose. The behaviour is older than
the decision; the decision records the basis.

### Who reads them

`AuditAdminController.java:28` requires the role `CLUB_ADMINISTRATOR` or `SYSTEM_ADMINISTRATOR`. The
audit-trail screen shows the rows of the club of the caller only.

### How long the application keeps them

The application keeps these values for the life of the audit row. No scheduled job deletes an audit
row, and the database refuses a DELETE on the table
(`V54__split_app_role_append_only_audit.sql:27`). The column-level UPDATE grant of V60 covers
`client_ip` alone, so the application cannot change or remove a licence value in the trail.

An erasure request for a licence value needs a database operation outside the application. The
operator owns that step.

## Related decisions

- [ADR 0030](adrs/0030-personal-data-in-the-mutation-audit-trail.md) — why the client IP is in the
  audit table, and the rejected alternatives.
- [ADR 0008](adrs/0008-multi-tenancy-mechanism.md) — the tenant discriminator that keeps every audit
  row inside one club.
