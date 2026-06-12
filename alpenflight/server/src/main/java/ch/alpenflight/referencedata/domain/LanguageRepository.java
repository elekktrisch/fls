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
}
