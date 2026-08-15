package ch.alpenflight.referencedata.domain;

import java.util.List;

public interface FlightCrewTypeRepository {

    List<FlightCrewType> findAllByOrderByLegacyIntIdAsc();
}
