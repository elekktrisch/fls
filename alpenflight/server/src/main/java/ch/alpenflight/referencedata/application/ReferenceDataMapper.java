package ch.alpenflight.referencedata.application;

import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.AircraftTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.ClubStateResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CounterUnitTypeResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.CountryResponse;
import ch.alpenflight.referencedata.application.ReferenceDataDtos.LocationTypeResponse;
import ch.alpenflight.referencedata.domain.AircraftState;
import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.ClubState;
import ch.alpenflight.referencedata.domain.CounterUnitType;
import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.LocationType;
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

    static LocationTypeResponse toLocationTypeResponse(LocationType type) {
        return new LocationTypeResponse(
                Objects.requireNonNull(type.getId(), "Cannot map a LocationType without id"),
                type.getCode(),
                type.getDescription(),
                type.isAirfield());
    }

    static AircraftTypeResponse toAircraftTypeResponse(AircraftType type) {
        return new AircraftTypeResponse(
                Objects.requireNonNull(type.getId(), "Cannot map an AircraftType without id"),
                type.getCode(),
                type.getDescription(),
                type.getHasEngine(),
                type.getRequiresTowingInfo(),
                type.getMayBeTowingAircraft());
    }

    static AircraftStateResponse toAircraftStateResponse(AircraftState state) {
        return new AircraftStateResponse(
                Objects.requireNonNull(state.getId(), "Cannot map an AircraftState without id"),
                state.getCode(),
                state.getDescription(),
                state.isAircraftFlyable());
    }

    static CounterUnitTypeResponse toCounterUnitTypeResponse(CounterUnitType unit) {
        return new CounterUnitTypeResponse(
                Objects.requireNonNull(unit.getId(), "Cannot map a CounterUnitType without id"),
                unit.getCode(),
                unit.getName(),
                unit.getShortName(),
                unit.getComment());
    }
}
