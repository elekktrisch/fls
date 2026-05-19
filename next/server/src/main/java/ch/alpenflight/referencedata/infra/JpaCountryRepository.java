package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data adapter for {@link CountryRepository}. The {@code findAllOrdered}
 * sort uses Postgres ICU collation {@code "de-CH-x-icu"} so accented
 * Latin characters (Côte d'Ivoire, Curaçao, Réunion) sort inside their
 * letter group rather than at the end as default C collation would
 * place them. Native query because JPQL has no portable {@code COLLATE}.
 */
public interface JpaCountryRepository extends JpaRepository<Country, UUID>, CountryRepository {

    @Override
    @Query(value = "SELECT * FROM country ORDER BY name COLLATE \"de-CH-x-icu\"",
            nativeQuery = true)
    List<Country> findAllOrdered();
}
