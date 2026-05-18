package ch.alpenflight.platform.hello;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Hello", description = "Liveness-style smoke endpoint; worked example for OpenAPI annotation conventions.")
public class HelloController {

    @Operation(
            summary = "Return a static greeting and the server timestamp.",
            description = "Authenticated smoke endpoint; worked example for OpenAPI annotation conventions.")
    @ApiResponse(responseCode = "200", description = "Greeting payload.")
    @GetMapping(value = "/api/v1/hello", produces = MediaType.APPLICATION_JSON_VALUE)
    public HelloResponse hello() {
        return new HelloResponse("Hello AlpenFlight", Instant.now());
    }
}
