---
id: S-082
title: JavaMailSender + Thymeleaf baseline + mailpit in compose
epic: E-10
status: todo
depends_on: [S-001, S-039]
integration_base: integration/users-suite
acceptance:
  - `spring-boot-starter-mail` + Thymeleaf dependencies in place.
  - Mailpit container in `docker-compose.yml` for dev/e2e; SMTP configured against it by default.
  - A worked-example email template under `src/main/resources/templates/email/test.html`.
  - A test sends the test template via `JavaMailSender`, captures via Mailpit's API, asserts subject + body.
estimate: S
adr_refs: [0013]
parity_test: tests/email/08-email.spec.ts (legacy)
---

## Context
ADR 0013 baseline. Required by S-084..S-088 + S-090, and pulled forward on `integration/users-suite` as a prerequisite for the join-request slice (S-178) which sends 4 transactional email templates (admin-on-new-request, pilot-on-approve, pilot-on-deny, pilot-on-withdraw). Mailpit landed separately via S-172; this story owns the app-layer JavaMailSender + Thymeleaf wiring on top.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Add dependencies.
- [ ] Add mailpit to compose.
- [ ] Configure Spring mail to Mailpit by default; env override for production.
- [ ] Worked-example template + test.

## Notes
The template layout convention is established here — pick single-file vs. fragment-composition.
Recommend: **fragment composition** with a base layout (`base-layout.html`) and per-mail bodies. Easier to keep the email-look consistent across all senders.
