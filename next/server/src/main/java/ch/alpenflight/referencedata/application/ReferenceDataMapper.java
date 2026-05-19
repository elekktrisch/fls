package ch.alpenflight.referencedata.application;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.ClubStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CountryResponse;
import ch.alpenflight.referencedata.domain.ClubState;
import ch.alpenflight.referencedata.domain.Country;
import java.util.Objects;

final class ReferenceDataMapper {

    private ReferenceDataMapper() {}

    static CountryResponse toResponse(Country country) {
        return new CountryResponse(
                Objects.requireNonNull(country.getId(), "Cannot map a Country without id"),
                country.getIso2Code(),
                country.getName());
    }

    static ClubStateResponse toResponse(ClubState clubState) {
        return new ClubStateResponse(
                Objects.requireNonNull(clubState.getId(), "Cannot map a ClubState without id"),
                clubState.getCode(),
                clubState.getName());
    }
}
