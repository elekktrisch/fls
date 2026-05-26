package ch.alpenflight.users.infra.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Glue config for the Keycloak admin-REST adapter — binds
 * {@link KeycloakAdminProperties} and provides a {@link RestClient.Builder}
 * default so the unit tests can override it without bringing up the full
 * Spring Boot auto-config.
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfig {

    @Bean
    RestClient.Builder keycloakAdminRestClientBuilder() {
        return RestClient.builder();
    }
}
