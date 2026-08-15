package ch.alpenflight.me.web;

import ch.alpenflight.persons.application.SelfLicencesView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

@Schema(description = "Caller's own Person licence/medical fields (read shape for the Pilot tab).")
record MePersonLicencesResponse(
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

    static MePersonLicencesResponse from(SelfLicencesView v) {
        return new MePersonLicencesResponse(
                v.hasMotorPilotLicence(),
                v.hasTowPilotLicence(),
                v.hasGliderInstructorLicence(),
                v.hasGliderPilotLicence(),
                v.hasGliderTraineeLicence(),
                v.hasGliderPaxLicence(),
                v.hasTmgLicence(),
                v.hasWinchOperatorLicence(),
                v.hasMotorInstructorLicence(),
                v.hasPartMLicence(),
                v.licenceNumber(),
                v.medicalClass1ExpireDate(),
                v.medicalClass2ExpireDate(),
                v.medicalLaplExpireDate(),
                v.gliderInstructorLicenceExpireDate(),
                v.motorInstructorLicenceExpireDate(),
                v.partMLicenceExpireDate(),
                v.hasGliderTowingStartPermission(),
                v.hasGliderSelfStartPermission(),
                v.hasGliderWinchStartPermission(),
                v.receiveOwnedAircraftStatisticReports());
    }
}
