package ch.alpenflight.platform.status;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemStatusController {

    private final Clock clock;

    public SystemStatusController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/v1/system/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse("UP", Instant.now(clock));
    }
}
