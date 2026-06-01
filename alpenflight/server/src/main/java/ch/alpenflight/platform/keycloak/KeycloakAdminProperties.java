package ch.alpenflight.platform.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config knobs for the backend → Keycloak admin client. Bound at
 * {@code keycloak.admin.*} in {@code application.yml}. The client secret
 * comes from {@code ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET} in production; the
 * dev profile defaults to the committed placeholder.
 *
 * @param baseUrl     base Keycloak URL — e.g. {@code http://keycloak:8080}
 * @param realm       realm name — {@code alpenflight}
 * @param clientId    confidential machine-client id — {@code alpenflight-backend-admin}
 * @param clientSecret service-account secret
 * @param refreshSkewSeconds rotate the cached token this many seconds before its expiry
 */
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
