package ch.alpenflight.users.infra.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link KeycloakAdminProperties} from {@code keycloak.admin.*}.
 * The adapters build their own {@code RestClient} internally so no shared
 * HTTP bean is published here.
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminConfig {
}
