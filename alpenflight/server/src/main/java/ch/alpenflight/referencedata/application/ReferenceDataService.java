package ch.alpenflight.referencedata.application;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.ClubStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CounterUnitTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CountryResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.LocationTypeResponse;
import ch.alpenflight.referencedata.domain.AircraftStateRepository;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CounterUnitTypeRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only orchestration for the reference-data catalogs. One service
 * for the whole module is enough at the slim-slice scale — there are no
 * write paths, no domain rules to encode, and the two GET endpoints
 * share the same shape (load + map).
 */
@Service
@Transactional(readOnly = true)
public class ReferenceDataService {

    private final CountryRepository countries;
    private final ClubStateRepository clubStates;
    private final LocationTypeRepository locationTypes;
    private final AircraftTypeRepository aircraftTypes;
    private final AircraftStateRepository aircraftStates;
    private final CounterUnitTypeRepository counterUnitTypes;

    public ReferenceDataService(CountryRepository countries,
                                ClubStateRepository clubStates,
                                LocationTypeRepository locationTypes,
                                AircraftTypeRepository aircraftTypes,
                                AircraftStateRepository aircraftStates,
                                CounterUnitTypeRepository counterUnitTypes) {
        this.countries = countries;
        this.clubStates = clubStates;
        this.locationTypes = locationTypes;
        this.aircraftTypes = aircraftTypes;
        this.aircraftStates = aircraftStates;
        this.counterUnitTypes = counterUnitTypes;
    }

    public List<CountryResponse> listCountries() {
        return countries.findAllOrdered().stream().map(ReferenceDataMapper::toResponse).toList();
    }

    public List<ClubStateResponse> listClubStates() {
        return clubStates.findAllOrdered().stream().map(ReferenceDataMapper::toResponse).toList();
    }

    public List<LocationTypeResponse> listLocationTypes() {
        return locationTypes.findAllByOrderByDescriptionAsc().stream()
                .map(ReferenceDataMapper::toLocationTypeResponse)
                .toList();
    }

    public List<AircraftTypeResponse> listAircraftTypes() {
        return aircraftTypes.findAllByOrderByLegacyIntIdAsc().stream()
                .map(ReferenceDataMapper::toAircraftTypeResponse)
                .toList();
    }

    public List<AircraftStateResponse> listAircraftStates() {
        return aircraftStates.findAllByOrderByLegacyIntIdAsc().stream()
                .map(ReferenceDataMapper::toAircraftStateResponse)
                .toList();
    }

    public List<CounterUnitTypeResponse> listCounterUnitTypes() {
        return counterUnitTypes.findAllByOrderByCodeAsc().stream()
                .map(ReferenceDataMapper::toCounterUnitTypeResponse)
                .toList();
    }
}
