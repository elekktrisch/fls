package ch.alpenflight.users.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Command for {@link UsersService#updateOwnProfile} — the caller-scoped
 * Account self-edit (J-4 T-04). Carries ONLY the self-editable fields of the
 * User aggregate. {@code remarks} is intentionally absent: it is admin-only
 * and the service preserves the existing value unchanged.
 */
public record SelfProfileUpdate(
        String friendlyName,
        String notificationEmail,
        @Nullable String phoneNumber,
        UUID languageId) {}
