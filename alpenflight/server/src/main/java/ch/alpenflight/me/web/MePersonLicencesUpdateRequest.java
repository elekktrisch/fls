package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Wire payload for {@code PATCH /api/v1/me/person/licences} — the caller-scoped
 * Person licence/medical self-edit (J-4 T-08, the FADP-sensitive Pilot tab).
 * Carries ONLY the Person aggregate's licence flags, licence number, medical /
 * instructor / part-M expiry dates and glider start-permission flags.
 *
 * <p>The boolean flags are nullable {@code Boolean} (not primitives) so an
 * absent flag deserialises cleanly to {@code null} → coerced to {@code false}
 * in the controller ({@code Boolean.TRUE.equals(...)}, the same pattern
 * {@link MePersonUpdateRequest#preferMailToBusinessMail} uses). The tab POSTs
 * the full pilot-profile shape (every checkbox), so an unchecked box still
 * lands as {@code false} — "replace the whole licence set" semantics, matching
 * the legacy person form.
 *
 * <p>Deliberately absent: contact / address fields (their own
 * {@code PATCH /me/person} surface, T-06), the name fields (admin-only), and
 * membership / role fields (admin-only). Binding to this record rather than the
 * {@link ch.alpenflight.persons.domain.Person} aggregate makes mass-assignment
 * of those fields structurally impossible.
 *
 * <p>The {@code licenceNumber} size mirrors the {@code Person} column; the
 * aggregate's {@code updateLicences} re-validates so an out-of-range value that
 * slips past bean validation still surfaces as a clean 400.
 */
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
