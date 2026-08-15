package ch.alpenflight.referencedata.domain;

import java.util.Optional;
import java.util.UUID;

public interface LanguageRepository {

    Optional<Language> findById(UUID id);

    Optional<UUID> findIdByCodeIgnoreCase(String code);
}
