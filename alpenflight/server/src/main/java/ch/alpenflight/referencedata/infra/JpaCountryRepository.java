package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data adapter for {@link CountryRepository}. The {@code findAllOrdered}
 * sort relies on the ICU collation {@code "de-CH-x-icu"} attached to the
 * {@code t_country.name} COLUMN (Flyway V40) so accented Latin characters
 * (Côte d'Ivoire, Curaçao, Réunion) sort inside their letter group rather than
 * after the ASCII range as the default collation would place them. Because the
 * collation lives on the column, a plain JPQL {@code ORDER BY} yields ICU
 * order — no {@code nativeQuery} / {@code COLLATE} clause needed (ADR 0027).
 */
public interface JpaCountryRepository extends JpaRepository<Country, UUID>, CountryRepository {

    @Override
    @Query("SELECT c FROM Country c ORDER BY c.name")
    List<Country> findAllOrdered();
}
