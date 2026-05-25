package ch.alpenflight.me.application;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Application-layer projection returned by {@link MeService#resolve}. The
 * {@code web} layer maps this to the {@code MeResponse} DTO (external
 * id-strings, JSON shape).
 */
public record MeView(
        @Nullable UUID userId,
        @Nullable UUID personId,
        @Nullable UUID clubId,
        List<String> roles,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String email,
        @Nullable String username) {}
