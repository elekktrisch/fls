package ch.alpenflight.referencedata.application;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.ClubStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CountryResponse;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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

    public ReferenceDataService(CountryRepository countries, ClubStateRepository clubStates) {
        this.countries = countries;
        this.clubStates = clubStates;
    }

    public List<CountryResponse> listCountries() {
        return countries.findAllOrdered().stream().map(ReferenceDataMapper::toResponse).toList();
    }

    public List<ClubStateResponse> listClubStates() {
        return clubStates.findAllOrdered().stream().map(ReferenceDataMapper::toResponse).toList();
    }
}
