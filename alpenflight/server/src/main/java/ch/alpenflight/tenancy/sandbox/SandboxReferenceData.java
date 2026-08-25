package ch.alpenflight.tenancy.sandbox;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.domain.MemberStateRepository;
import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.platform.id.AircraftTypeId;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.referencedata.application.ReferenceDataService;
import ch.alpenflight.tenancy.provisioning.application.ReferenceDataSeeder;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class SandboxReferenceData {

    private final ReferenceDataSeeder perClubDefaults;
    private final ReferenceDataService globalCatalog;
    private final MemberStateRepository memberStatesOfTheCurrentTenant;

    SandboxReferenceData(ReferenceDataSeeder perClubDefaults,
                         ReferenceDataService globalCatalog,
                         MemberStateRepository memberStatesOfTheCurrentTenant) {
        this.perClubDefaults = perClubDefaults;
        this.globalCatalog = globalCatalog;
        this.memberStatesOfTheCurrentTenant = memberStatesOfTheCurrentTenant;
    }

    void seedTheDefaultsEveryClubNeeds(UUID clubId) {
        perClubDefaults.seedDefaults(clubId);
    }

    CountryId countryOfIso2Code(String iso2Code) {
        return globalCatalog.listCountries().stream()
                .filter(country -> iso2Code.equals(country.iso2Code()))
                .findFirst()
                .orElseThrow(() -> absentReferenceRow("country", iso2Code))
                .id();
    }

    LocationTypeId locationTypeOfCode(String code) {
        return globalCatalog.listLocationTypes().stream()
                .filter(locationType -> code.equals(locationType.code()))
                .findFirst()
                .orElseThrow(() -> absentReferenceRow("location type", code))
                .id();
    }

    AircraftTypeId aircraftTypeOfCode(String code) {
        return globalCatalog.listAircraftTypes().stream()
                .filter(aircraftType -> code.equals(aircraftType.code()))
                .findFirst()
                .orElseThrow(() -> absentReferenceRow("aircraft type", code))
                .id();
    }

    AircraftStateId aircraftStateOfCode(String code) {
        return globalCatalog.listAircraftStates().stream()
                .filter(aircraftState -> code.equals(aircraftState.code()))
                .findFirst()
                .orElseThrow(() -> absentReferenceRow("aircraft state", code))
                .id();
    }

    UUID memberStateOfName(String name) {
        MemberState found = memberStatesOfTheCurrentTenant.findAll().stream()
                .filter(memberState -> name.equals(memberState.getName()))
                .findFirst()
                .orElseThrow(() -> absentReferenceRow("member state", name));
        UUID id = found.getId();
        if (id == null) {
            throw new IllegalStateException("the member state '" + name + "' carries no id");
        }
        return id;
    }

    private static IllegalStateException absentReferenceRow(String catalog, String key) {
        return new IllegalStateException(
                "the sandbox seed needs the " + catalog + " '" + key + "', which is absent");
    }
}
