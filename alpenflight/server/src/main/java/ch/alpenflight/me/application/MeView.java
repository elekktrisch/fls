package ch.alpenflight.me.application;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MeView(
        @Nullable UUID userId,
        @Nullable UUID personId,
        @Nullable UUID clubId,
        List<String> roles,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String email,
        @Nullable String username,
        @Nullable String friendlyName,
        @Nullable String phoneNumber,
        @Nullable UUID languageId,
        @Nullable String languageCode,
        @Nullable UUID homebaseLocationId) {}
