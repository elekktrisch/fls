package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Language;
import ch.alpenflight.referencedata.domain.LanguageRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data adapter for {@link LanguageRepository}. The port's single
 * finder ({@code findById}) is satisfied by the inherited
 * {@link JpaRepository#findById} — no query methods of its own.
 */
public interface JpaLanguageRepository extends JpaRepository<Language, UUID>, LanguageRepository {
}
