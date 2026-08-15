package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Language;
import ch.alpenflight.referencedata.domain.LanguageRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaLanguageRepository extends JpaRepository<Language, UUID>, LanguageRepository {

    @Override
    @Query("SELECT l.id FROM Language l WHERE lower(l.code) = lower(:code)")
    Optional<UUID> findIdByCodeIgnoreCase(@Param("code") String code);
}
