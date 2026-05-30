package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.KnownMappers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link EntityStreamIngestor} bean. Spelled out as a
 * {@code @Bean} factory (rather than constructor-inlined inside
 * {@link MigrationBundleIngestService}) so a {@code @TestConfiguration}
 * can override the bean — driving a faulty / slow mapper for the
 * S-141b rollback-trail + timeout integration tests without exposing
 * the package-private constructor surface to test code.
 *
 * <p>Production wiring is unchanged: every concrete mapper from
 * {@link KnownMappers#all()} is registered, and the constructor-time
 * column allow-list validator fails Spring boot on any column outside
 * {@code [A-Za-z0-9_]+}.
 */
@Configuration
class MigrationBundleIngestApplicationConfig {

    @Bean
    EntityStreamIngestor entityStreamIngestor() {
        return new EntityStreamIngestor(KnownMappers.all());
    }
}
