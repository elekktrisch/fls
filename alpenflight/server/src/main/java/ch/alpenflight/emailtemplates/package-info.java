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
 * <p>Layered per ADR 0023; T-03 ships only {@code domain} (aggregate +
 * repository port). The {@code application} / {@code web} / {@code infra}
 * REST + resolver slices land in J-11 T-04/T-05.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.emailtemplates;
