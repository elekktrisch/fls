package ch.alpenflight.users.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SelfProfileUpdate(
        String friendlyName,
        String notificationEmail,
        @Nullable String phoneNumber,
        UUID languageId) {}
