package ch.alpenflight.tenancy.sandbox.domain;

public class DemoSeatTokenNotIssuedException extends RuntimeException {

    private static final String READABLE_REASON_THAT_NAMES_NO_IDENTITY_PROVIDER_INTERNALS =
            "The demo cannot start at the moment. Please try again in a few minutes.";

    public DemoSeatTokenNotIssuedException(String messageWithoutTheIdentityProviderResponseBody) {
        super(messageWithoutTheIdentityProviderResponseBody);
    }

    public DemoSeatTokenNotIssuedException(String messageWithoutTheIdentityProviderResponseBody,
                                           Throwable cause) {
        super(messageWithoutTheIdentityProviderResponseBody, cause);
    }

    public String readableReason() {
        return READABLE_REASON_THAT_NAMES_NO_IDENTITY_PROVIDER_INTERNALS;
    }
}
