# 0013 — Email-sending library + templating

- **Status:** Accepted
- **Date:** 2026-05-14
- **Decision criteria** (from [vision §6](../02-vision-and-constraints.md#6-decision-criteria-for-phase-3)): solo-operator operability · mature ecosystem · preserves sacred cows (planning-day mails, daily reports, licence warnings, delivery exports)

## Context

Current email pipeline uses `System.Net.Mail.SmtpClient` + the vendored `Alpinely.TownCrier` templating library ([current-state §6 Server](../01-current-state.md#server), [seed § "Vendored libs"](../00-seed.md#sacred-cows-must-survive-the-rewrite)). `Alpinely.TownCrier` is a checked-in copy with unverified license and provenance ([R11](../01-current-state.md#r11--vendored-email-templating-lib-alpinelytowncrier)) — replacing it is on the modernization checklist.

On the JVM with Spring Boot 4 ([ADR 0001](0001-backend-language-and-framework.md)), the natural pairing is `spring-boot-starter-mail` (which provides `JavaMailSender`) + Thymeleaf for templating. Thymeleaf is the canonical Spring template engine; it also has long-standing support for HTML email templates via the same expression syntax it uses for web pages.

This ADR covers the **library + templating** layer. The **SMTP relay / sending infrastructure** (Mailpit in dev; production relay choice — self-hosted Postal vs. transactional API like Resend/Postmark/Mailgun/Brevo) is a deployment-time concern tracked as a follow-up.

## Options considered

### Option A — Spring `JavaMailSender` + Thymeleaf templates
- **Capabilities:** `spring-boot-starter-mail` exposes `JavaMailSender` injectable everywhere; `MimeMessageHelper` builds messages with attachments / inline images. Thymeleaf renders HTML templates from `src/main/resources/templates/email/*.html` with the model object as context; supports localization via `spring.messages` (the same i18n surface as the web side, if shared).
- **Fit to criteria:** operability ✓ (one Spring starter + one templating library; both already in the application's dependency graph for other reasons). Mature ecosystem ✓ (default Spring stack). Preserves sacred cows ✓ (the templates are migrated content; the sending mechanism is interchangeable).
- **Migration cost:** medium — port each existing email template from `Alpinely.TownCrier` syntax to Thymeleaf. The current templates live in `flsserver/src/FLS.Server.Service/Email/` (see [SERVER.md "Job catalog"](../../legacy/server.md)) — content migrates, syntax changes.
- **Ecosystem risk:** low.
- **Escape hatch:** templating engine is swappable (Mustache, Pebble, Freemarker) without changing the sending API. Sending API is swappable to Simple Java Mail or transactional APIs without changing the templates.

### Option B — Simple Java Mail + Thymeleaf / Mustache
- **Capabilities:** more fluent API than `JavaMailSender`, but functionally equivalent.
- **Why not chosen:** the API ergonomic improvement isn't worth a non-default dependency when Spring's wrapper already covers our needs.

### Option C — Transactional email API service (Resend / Postmark / Mailgun / Brevo)
- **Why not chosen as a library decision:** these are sending-infrastructure choices, not library choices. The application still uses Spring's mail abstraction or the vendor's SDK; templating is still a separate concern. Belongs in the prod-relay follow-up.

### Option D — Plain JavaMail (`jakarta.mail`) without Spring abstraction
- **Why not chosen:** no reason to skip Spring's thin wrapper that's already provided by `spring-boot-starter-mail`.

## Decision

Chosen: **Option A — Spring `JavaMailSender` + Thymeleaf templates**. Default Spring stack; same templating engine works for web pages if we ever need that; sending abstraction lets us swap SMTP relay or move to a transactional API without changing call sites.

## Consequences

- **Positive:**
  - `Alpinely.TownCrier` vendored copy goes away ([R11](../01-current-state.md#r11--vendored-email-templating-lib-alpinelytowncrier) closed).
  - Templates live in `src/main/resources/templates/email/` — versioned, reviewable, testable in isolation.
  - `JavaMailSender` is mockable in tests; emails can be asserted by content without a real SMTP server.
  - Localization integrates with the Spring i18n surface — if a future ADR moves any i18n to server-side messages, emails benefit too. For now, with [C15](../02-vision-and-constraints.md#3-hard-constraints) bundling i18n in the frontend, emails carry their own message bundle.
  - Switching SMTP relay (Mailpit ↔ Postal ↔ Postmark ↔ Resend) is a config change, not a code change.

- **Negative:**
  - Thymeleaf adds a dependency for what could in principle be string-templated; the safety / escaping / localization features earn their keep.
  - HTML-email rendering quirks (Outlook compatibility, dark mode, inline CSS) remain a real-world pain — same as today, but Thymeleaf doesn't help or hurt.
  - Each existing template needs to be ported by hand from `Alpinely.TownCrier` syntax to Thymeleaf. No automated path.

- **Follow-ups (other ADRs / stories implied):**
  - **Story:** add `spring-boot-starter-mail` and Thymeleaf to the backend dependency graph.
  - **Story:** scaffold `src/main/resources/templates/email/` with one example template (e.g. planning-day reminder); establish the layout convention (single-file vs. partial-fragments).
  - **Story:** inventory every email template currently produced (planning-day notifications, daily reports, licence-expiry warnings, password reset — handled by the IdP per [ADR 0007](0007-auth-scheme.md), test mails, monthly aircraft stat, delivery export wrapper). Port each.
  - **Story:** test infrastructure — Spring Boot test that captures `JavaMailSender` output and asserts subject, recipients, body content for each email-emitting service.
  - **Story (deployment-time):** decide the **production SMTP relay**. Candidates: self-hosted Postal on the same VPS (free, full control, residency-clean), Postmark (EU region, transactional reputation), Resend (EU region, modern API), Brevo (EU-headquartered). Criteria: deliverability, EU residency, bounce/complaint handling, cost at our scale. Defer to deployment readiness.
  - **Story (dev):** wire Mailpit into `docker-compose.yml` for the e2e and developer workflows — matches the current e2e stack ([TESTING.md](../../TESTING.md)).
  - **Story:** decommission the `Alpinely.TownCrier/` vendored folder from the modernization-scope notes — replaced by Thymeleaf.
  - **Story:** define the per-tenant + per-locale email sender identity (e.g. `noreply+{clubId}@fls.app`) and DKIM/SPF setup; relates to deliverability rather than library choice but worth tracking now.
