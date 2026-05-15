package ch.fls.platform.hello;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(S-020): remove or auth-gate before cutover.
@RestController
public class HelloController {

    public record HelloResponse(String message, Instant timestamp) {}

    @GetMapping("/api/v1/hello")
    public HelloResponse hello() {
        return new HelloResponse("Hello FLS", Instant.now());
    }
}
