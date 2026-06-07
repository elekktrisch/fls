/**
 * Shared-kernel email send-path (J-6 T-10a, ADR 0013). The AlpenFlight
 * equivalent of legacy's {@code System.Net.Mail.SmtpClient} + the vendored
 * {@code Alpinely.TownCrier} templating — replaced here by Spring's
 * {@code JavaMailSender} + Thymeleaf.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@link ch.alpenflight.platform.mail.MailSender} — the port a business
 *       module depends on to send a fully-rendered message. Implemented by
 *       {@link ch.alpenflight.platform.mail.SmtpMailSender} (production) and
 *       overridden by a {@code @Primary} captured-outbox fake in ITs.</li>
 *   <li>{@link ch.alpenflight.platform.mail.TemplatedMailService} — the
 *       build-service: renders a named Thymeleaf template
 *       ({@code templates/email/<name>.html}) against a model map, then hands
 *       the result to the {@code MailSender} port.</li>
 *   <li>{@link ch.alpenflight.platform.mail.MailMessage} — the rendered-message
 *       value object that crosses the port.</li>
 * </ul>
 *
 * <p>This package lives under the OPEN {@code platform} module (ADR 0023
 * shared-kernel exception), so any business module — e.g. the future planning
 * notification job (T-10c) — may import it directly without a named interface.
 * It carries INFRA only: no business templates, no scheduled job (those ride
 * T-10b / T-10c).
 */
@NullMarked
package ch.alpenflight.platform.mail;

import org.jspecify.annotations.NullMarked;
