package ch.alpenflight.server.testsupport;

import ch.alpenflight.emailtemplates.domain.EmailTemplate;

/**
 * Minimal-object factory for {@link EmailTemplate} consumed by the S-024
 * leakage sweep. The override identity is per-tenant unique (V47 UNIQUE on
 * {@code (club_id, template_key, language_locale)}); the nanoTime suffix keeps
 * cross-test runs isolated within a tenant. {@code club_id} is the only FK and
 * is the {@code @TenantId} discriminator the resolver fills — no reference data
 * to look up.
 */
final class EmailTemplateSweepFactory {

    private EmailTemplateSweepFactory() {}

    @SuppressWarnings("unused") // ctx unused — EmailTemplate has no FK reference data to look up.
    static EmailTemplate build(SweepFixtureContext ctx) {
        String unique = Long.toString(System.nanoTime(), 36);
        return EmailTemplate.customize(
                TenantScopedRowBuilders.SWEEP_PREFIX + "eml_" + unique,
                "de",
                "Sweep subject",
                "<p>Sweep body</p>");
    }
}
