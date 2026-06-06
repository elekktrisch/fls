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
        @Nullable String username,
        // Account self-edit (J-4): the User aggregate's mutable self-fields,
        // sourced from the t_user row. Null when no user row matches the JWT
        // sub (sysadmin / unmapped federated principal) — the Account form
        // falls back to the username/email JWT claims and disables save.
        @Nullable String friendlyName,
        @Nullable String phoneNumber,
        @Nullable UUID languageId,
        // The BCP-47 code of {@code languageId} (joined from t_language) — lets
        // the SPA flip its active locale on a saved language change without a
        // second round-trip.
        @Nullable String languageCode) {}
