package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.KnownMappers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MigrationBundleIngestApplicationConfig {

    @Bean
    EntityStreamIngestor entityStreamIngestor() {
        return new EntityStreamIngestor(KnownMappers.all());
    }
}
