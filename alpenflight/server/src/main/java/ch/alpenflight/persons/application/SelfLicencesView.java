package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.Person;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

public record SelfLicencesView(
        boolean hasMotorPilotLicence,
        boolean hasTowPilotLicence,
        boolean hasGliderInstructorLicence,
        boolean hasGliderPilotLicence,
        boolean hasGliderTraineeLicence,
        boolean hasGliderPaxLicence,
        boolean hasTmgLicence,
        boolean hasWinchOperatorLicence,
        boolean hasMotorInstructorLicence,
        boolean hasPartMLicence,
        @Nullable String licenceNumber,
        @Nullable LocalDate medicalClass1ExpireDate,
        @Nullable LocalDate medicalClass2ExpireDate,
        @Nullable LocalDate medicalLaplExpireDate,
        @Nullable LocalDate gliderInstructorLicenceExpireDate,
        @Nullable LocalDate motorInstructorLicenceExpireDate,
        @Nullable LocalDate partMLicenceExpireDate,
        boolean hasGliderTowingStartPermission,
        boolean hasGliderSelfStartPermission,
        boolean hasGliderWinchStartPermission,
        boolean receiveOwnedAircraftStatisticReports) {

    public static SelfLicencesView of(Person p) {
        return new SelfLicencesView(
                p.hasMotorPilotLicence(),
                p.hasTowPilotLicence(),
                p.hasGliderInstructorLicence(),
                p.hasGliderPilotLicence(),
                p.hasGliderTraineeLicence(),
                p.hasGliderPaxLicence(),
                p.hasTmgLicence(),
                p.hasWinchOperatorLicence(),
                p.hasMotorInstructorLicence(),
                p.hasPartMLicence(),
                p.getLicenceNumber(),
                p.getMedicalClass1ExpireDate(),
                p.getMedicalClass2ExpireDate(),
                p.getMedicalLaplExpireDate(),
                p.getGliderInstructorLicenceExpireDate(),
                p.getMotorInstructorLicenceExpireDate(),
                p.getPartMLicenceExpireDate(),
                p.hasGliderTowingStartPermission(),
                p.hasGliderSelfStartPermission(),
                p.hasGliderWinchStartPermission(),
                p.isReceiveOwnedAircraftStatisticReports());
    }
}
