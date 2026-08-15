package ch.alpenflight.platform.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class ProblemResponses {

    private static final URI TYPE_BAD_REQUEST = URI.create("urn:alpenflight:problem:bad-request");

    private ProblemResponses() {
    }

    public static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }

    public static ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_BAD_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }
}
