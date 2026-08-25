package ch.alpenflight.tenancy.sandbox.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.direct-grant")
public record DemoSeatDirectGrantProperties(
        String clientId,
        String clientSecret,
        String seatPassword) {

    public static final String CLIENT_SECRET_THE_COMMITTED_DEV_REALM_CARRIES =
            "alpenflight-demo-seat-dev-secret";

    public static final String SEAT_PASSWORD_THE_COMMITTED_DEV_REALM_CARRIES =
            "alpenflight-demo-seat-dev-2026!";

    public DemoSeatDirectGrantProperties {
        requireAValue("demo.direct-grant.client-id", clientId);
        requireAValue("demo.direct-grant.client-secret", clientSecret);
        requireAValue("demo.direct-grant.seat-password", seatPassword);
    }

    public boolean carriesACredentialThatTheCommittedDevRealmPublishes() {
        return CLIENT_SECRET_THE_COMMITTED_DEV_REALM_CARRIES.equals(clientSecret)
                || SEAT_PASSWORD_THE_COMMITTED_DEV_REALM_CARRIES.equals(seatPassword);
    }

    private static void requireAValue(String propertyKey, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyKey + " must be set");
        }
    }
}
