package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Language;
import ch.alpenflight.referencedata.domain.LanguageRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data adapter for {@link LanguageRepository}. {@code findById} is
 * satisfied by the inherited {@link JpaRepository#findById}; the
 * case-insensitive code lookup is an explicit JPQL projection so it returns
 * the bare id (no entity hydration) and matches case on both sides —
 * mirroring the retired {@code lower(code) = ?} JDBC predicate exactly.
 */
public interface JpaLanguageRepository extends JpaRepository<Language, UUID>, LanguageRepository {

    @Override
    @Query("SELECT l.id FROM Language l WHERE lower(l.code) = lower(:code)")
    Optional<UUID> findIdByCodeIgnoreCase(@Param("code") String code);
}
