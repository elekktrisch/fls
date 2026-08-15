package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

@Schema(description = "Person licence/medical self-edit payload — caller's own pilot/medical fields only.")
record MePersonLicencesUpdateRequest(
        @Schema(description = "Holds a motor-pilot licence (absent = false).")
        @Nullable Boolean hasMotorPilotLicence,
        @Schema(description = "Holds a tow-pilot licence (absent = false).")
        @Nullable Boolean hasTowPilotLicence,
        @Schema(description = "Holds a glider-instructor licence (absent = false).")
        @Nullable Boolean hasGliderInstructorLicence,
        @Schema(description = "Holds a glider-pilot licence (absent = false).")
        @Nullable Boolean hasGliderPilotLicence,
        @Schema(description = "Holds a glider-trainee licence (absent = false).")
        @Nullable Boolean hasGliderTraineeLicence,
        @Schema(description = "Holds a glider-passenger licence (absent = false).")
        @Nullable Boolean hasGliderPaxLicence,
        @Schema(description = "Holds a TMG (touring motor glider) licence (absent = false).")
        @Nullable Boolean hasTmgLicence,
        @Schema(description = "Holds a winch-operator licence (absent = false).")
        @Nullable Boolean hasWinchOperatorLicence,
        @Schema(description = "Holds a motor-instructor licence (absent = false).")
        @Nullable Boolean hasMotorInstructorLicence,
        @Schema(description = "Holds a Part-M licence (absent = false).")
        @Nullable Boolean hasPartMLicence,
        @Schema(description = "Pilot licence number.")
        @Nullable @Size(max = 20) String licenceNumber,
        @Schema(description = "Class-1 medical expiry date.")
        @Nullable LocalDate medicalClass1ExpireDate,
        @Schema(description = "Class-2 medical expiry date.")
        @Nullable LocalDate medicalClass2ExpireDate,
        @Schema(description = "LAPL medical expiry date.")
        @Nullable LocalDate medicalLaplExpireDate,
        @Schema(description = "Glider-instructor licence expiry date.")
        @Nullable LocalDate gliderInstructorLicenceExpireDate,
        @Schema(description = "Motor-instructor licence expiry date.")
        @Nullable LocalDate motorInstructorLicenceExpireDate,
        @Schema(description = "Part-M licence expiry date.")
        @Nullable LocalDate partMLicenceExpireDate,
        @Schema(description = "Has glider aerotow start permission (absent = false).")
        @Nullable Boolean hasGliderTowingStartPermission,
        @Schema(description = "Has glider self-launch start permission (absent = false).")
        @Nullable Boolean hasGliderSelfStartPermission,
        @Schema(description = "Has glider winch-launch start permission (absent = false).")
        @Nullable Boolean hasGliderWinchStartPermission,
        @Schema(description = "Receive owned-aircraft statistic reports (absent = false).")
        @Nullable Boolean receiveOwnedAircraftStatisticReports) {}
