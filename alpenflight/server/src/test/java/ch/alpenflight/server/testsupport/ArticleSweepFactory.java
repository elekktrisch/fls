package ch.alpenflight.server.testsupport;

import ch.alpenflight.articles.domain.Article;

final class ArticleSweepFactory {

    private ArticleSweepFactory() {}

    @SuppressWarnings("unused")
    static Article build(SweepFixtureContext ctx) {
        String unique = Long.toString(System.nanoTime(), 36);
        return Article.register(
                TenantScopedRowBuilders.SWEEP_PREFIX + "ART_" + unique,
                "Sweep Article",
                null,
                null,
                true);
    }
}
