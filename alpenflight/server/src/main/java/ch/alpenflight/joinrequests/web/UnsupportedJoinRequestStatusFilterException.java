package ch.alpenflight.joinrequests.web;

class UnsupportedJoinRequestStatusFilterException extends RuntimeException {

    UnsupportedJoinRequestStatusFilterException(String status) {
        super("Unsupported join-request status filter: " + status);
    }
}
