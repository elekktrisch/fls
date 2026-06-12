package ch.alpenflight.server.testsupport;

import ch.alpenflight.articles.domain.Article;

/**
 * Minimal-object factory for {@link Article} consumed by the S-024 leakage
 * sweep. Number uniqueness is per-tenant (V3 partial UNIQUE) — the suffix
 * mixes nanoTime + the SWEEP_PREFIX to keep cross-test runs isolated within
 * a tenant.
 */
final class ArticleSweepFactory {

    private ArticleSweepFactory() {}

    @SuppressWarnings("unused") // ctx unused — Article has no FK reference data to look up.
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
