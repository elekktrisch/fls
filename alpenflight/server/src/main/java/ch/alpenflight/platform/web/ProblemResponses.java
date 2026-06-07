package ch.alpenflight.platform.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Shared-kernel helpers for the RFC 7807 problem-response idiom every
 * per-resource {@code @RestControllerAdvice} repeated verbatim — the
 * {@code application/problem+json} response builder and the generic
 * {@code 400 bad-request} {@link ProblemDetail} factory.
 *
 * <p>Extracted at J-6 T-04b to kill the codebase-wide {@code problem(...)} /
 * {@code handleIllegalArgument(...)} copy-paste clones at source (mirrors the
 * J-6 T-02 {@code platform.text.FreeText} extraction). Each handler keeps its
 * own domain-exception → status/type/title mapping (the part that genuinely
 * differs); only the boilerplate moves here.
 */
public final class ProblemResponses {

    private static final URI TYPE_BAD_REQUEST = URI.create("urn:alpenflight:problem:bad-request");

    private ProblemResponses() {
    }

    /**
     * Wraps a {@link ProblemDetail} in the canonical
     * {@code application/problem+json} {@link ResponseEntity} (status taken from
     * the detail itself).
     */
    public static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }

    /**
     * The generic {@code 400 Bad Request} response for an
     * {@link IllegalArgumentException} (missing/invalid factory args) — the
     * fallback every handler maps identically.
     */
    public static ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_BAD_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }
}
