/**
 * Email-templates module — per-club overrides of transactional-email defaults
 * (subject + Thymeleaf body, keyed by {@code template_key} + locale). The
 * AlpenFlight DB holds ONLY overrides; the system defaults are S-082 Thymeleaf
 * files, never rows, so the send-time resolver reads file-default ∪ db-override
 * and reset is deleting the override row.
 *
 * <p>The {@code EmailTemplate} aggregate root is tenant-scoped via Hibernate's
 * {@code @TenantId} discriminator on {@code clubId}; reads + writes filter to
 * the caller's tenant structurally, never by convention. Identity is
 * {@code (club_id, template_key, language_locale)} — the structural UNIQUE in
 * V47. {@code template_key} + {@code language_locale} are canonicalized
 * lower-case on the aggregate so the override resolves case-insensitively.
 *
 * <p>Layered per ADR 0023 into {@code domain} (aggregate + repository port),
 * {@code application} (the union-read service + DTOs + the file-default
 * catalogue + mapper), {@code web} (REST controller + exception handler), and
 * {@code infra} (the Spring Data JPA adapter). The send-time resolver that
 * prefers the DB override over the file default is a separate slice.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.emailtemplates;
