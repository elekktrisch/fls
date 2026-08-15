package ch.alpenflight.platform.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret,
        int refreshSkewSeconds) {

    public KeycloakAdminProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("keycloak.admin.base-url must be set");
        }
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("keycloak.admin.realm must be set");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("keycloak.admin.client-id must be set");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("keycloak.admin.client-secret must be set");
        }
        if (refreshSkewSeconds < 0) {
            throw new IllegalArgumentException("keycloak.admin.refresh-skew-seconds must be ≥ 0");
        }
    }

    public String tokenEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String adminBase() {
        return baseUrl + "/admin/realms/" + realm;
    }
}
