package ch.alpenflight.joinrequests.web;

/**
 * Thrown when the admin pending-list is asked for a {@code status} filter
 * other than {@code pending} (S-178, T-05 serves only the pending list).
 * Translated to HTTP 400 — a typo surfaces instead of an empty list.
 */
class UnsupportedJoinRequestStatusFilterException extends RuntimeException {

    UnsupportedJoinRequestStatusFilterException(String status) {
        super("Unsupported join-request status filter: " + status);
    }
}
