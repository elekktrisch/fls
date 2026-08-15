package ch.alpenflight.referencedata.domain;

import java.util.List;
import java.util.UUID;

public interface CountryRepository {

    List<Country> findAllOrdered();

    boolean existsById(UUID id);
}
