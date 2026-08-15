package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.CountryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaCountryRepository extends JpaRepository<Country, UUID>, CountryRepository {

    @Override
    @Query("SELECT c FROM Country c ORDER BY c.name")
    List<Country> findAllOrdered();
}
