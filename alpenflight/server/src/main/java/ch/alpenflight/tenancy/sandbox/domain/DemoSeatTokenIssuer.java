package ch.alpenflight.tenancy.sandbox.domain;

public interface DemoSeatTokenIssuer {

    record IssuedAccessToken(String accessToken, long expiresInSeconds) {

        public IssuedAccessToken {
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken must not be blank");
            }
            if (expiresInSeconds <= 0) {
                throw new IllegalArgumentException(
                        "expiresInSeconds must be positive, was " + expiresInSeconds);
            }
        }
    }

    IssuedAccessToken issueAccessTokenForSeatPrincipal(String keycloakUsername);
}
