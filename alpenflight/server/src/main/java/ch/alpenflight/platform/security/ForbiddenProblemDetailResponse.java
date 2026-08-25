package ch.alpenflight.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

final class ForbiddenProblemDetailResponse {

    private final ObjectMapper objectMapper;

    ForbiddenProblemDetailResponse(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletResponse response, URI problemType, String piiFreeMessage)
            throws IOException {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(problemType);
        pd.setTitle(piiFreeMessage);
        pd.setDetail(piiFreeMessage);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
