package ch.fls.platform.hello;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO(S-020): remove or auth-gate before cutover.
@RestController
public class HelloController {

    @GetMapping("/api/v1/hello")
    public Map<String, String> hello() {
        return Map.of(
                "message", "Hello FLS",
                "timestamp", Instant.now().toString());
    }
}
