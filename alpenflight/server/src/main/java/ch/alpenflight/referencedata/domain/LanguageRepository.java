package ch.alpenflight.referencedata.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link Language} reads. Implemented by
 * {@code ch.alpenflight.referencedata.infra.JpaLanguageRepository}.
 *
 * <p>Read-only by design — Language rows are Flyway-managed (V2 seed) and
 * never written by the application.
 */
public interface LanguageRepository {

    /** The language row with the given id, if any. */
    Optional<Language> findById(UUID id);

    /**
     * The {@code language.id} for the given BCP-47 {@code code}, matched
     * case-insensitively (e.g. {@code de-ch} resolves the seeded
     * {@code de-CH} row), or empty when no row matches. Returns only the id
     * so the locale-to-language resolver stays off the entity-hydration path.
     * Used by {@code platform.tenancy.LanguageCodeLookup}.
     */
    Optional<UUID> findIdByCodeIgnoreCase(String code);
}
