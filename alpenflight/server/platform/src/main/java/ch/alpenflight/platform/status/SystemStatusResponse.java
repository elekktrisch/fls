package ch.alpenflight.platform.status;

import java.time.Instant;

public record SystemStatusResponse(String status, Instant serverTime) {
}
