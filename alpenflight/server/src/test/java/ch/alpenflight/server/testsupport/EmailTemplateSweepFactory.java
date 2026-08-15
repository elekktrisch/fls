package ch.alpenflight.server.testsupport;

import ch.alpenflight.emailtemplates.domain.EmailTemplate;

final class EmailTemplateSweepFactory {

    private EmailTemplateSweepFactory() {}

    @SuppressWarnings("unused")
    static EmailTemplate build(SweepFixtureContext ctx) {
        String unique = Long.toString(System.nanoTime(), 36);
        return EmailTemplate.customize(
                TenantScopedRowBuilders.SWEEP_PREFIX + "eml_" + unique,
                "de",
                "Sweep subject",
                "<p>Sweep body</p>");
    }
}
