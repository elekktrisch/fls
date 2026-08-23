package ch.alpenflight.tenancy.sandbox.web;

import static ch.alpenflight.platform.web.ProblemResponses.problem;

import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenNotIssuedException;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = DemoSessionController.class)
class DemoSessionExceptionHandler {

    static final URI TYPE_POOL_EXHAUSTED =
            URI.create("urn:alpenflight:problem:demo-pool-exhausted");

    static final URI TYPE_ADDRESS_HOLDS_A_SEAT =
            URI.create("urn:alpenflight:problem:demo-seat-already-held");

    static final URI TYPE_NO_TOKEN_ISSUED =
            URI.create("urn:alpenflight:problem:demo-seat-token-not-issued");

    static final String TITLE_POOL_EXHAUSTED = "No demo seat is free";

    static final String TITLE_ADDRESS_HOLDS_A_SEAT = "Your address holds a demo seat already";

    static final String TITLE_NO_TOKEN_ISSUED = "The demo cannot start";

    @ExceptionHandler(NoDemoSeatAvailableException.class)
    ResponseEntity<ProblemDetail> handleNoSeatAvailable(NoDemoSeatAvailableException e) {
        return problem(serviceUnavailable(typeOf(e), titleOf(e), e.readableReason()));
    }

    @ExceptionHandler(DemoSeatTokenNotIssuedException.class)
    ResponseEntity<ProblemDetail> handleNoTokenIssued(DemoSeatTokenNotIssuedException e) {
        return problem(serviceUnavailable(
                TYPE_NO_TOKEN_ISSUED, TITLE_NO_TOKEN_ISSUED, e.readableReason()));
    }

    private static ProblemDetail serviceUnavailable(URI type, String title, String readableReason) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setType(type);
        pd.setTitle(title);
        pd.setDetail(readableReason);
        return pd;
    }

    private static URI typeOf(NoDemoSeatAvailableException e) {
        return switch (e.reason()) {
            case THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT -> TYPE_ADDRESS_HOLDS_A_SEAT;
            case EVERY_DEMO_SEAT_IS_IN_USE,
                 TOO_MANY_VISITORS_CLAIMED_A_SEAT_AT_THE_SAME_MOMENT -> TYPE_POOL_EXHAUSTED;
        };
    }

    private static String titleOf(NoDemoSeatAvailableException e) {
        return switch (e.reason()) {
            case THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT -> TITLE_ADDRESS_HOLDS_A_SEAT;
            case EVERY_DEMO_SEAT_IS_IN_USE,
                 TOO_MANY_VISITORS_CLAIMED_A_SEAT_AT_THE_SAME_MOMENT -> TITLE_POOL_EXHAUSTED;
        };
    }
}
