package ch.alpenflight.platform.hello;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(S-020): remove or auth-gate before cutover.
@RestController
@Tag(name = "Hello", description = "Liveness-style smoke endpoint; worked example for OpenAPI annotation conventions.")
public class HelloController {

    @Operation(
            summary = "Return a static greeting and the server timestamp.",
            description = "Anonymous smoke endpoint; worked example for OpenAPI annotation conventions.")
    @ApiResponse(responseCode = "200", description = "Greeting payload.")
    @GetMapping("/api/v1/hello")
    public HelloResponse hello() {
        return new HelloResponse("Hello AlpenFlight", Instant.now());
    }
}
