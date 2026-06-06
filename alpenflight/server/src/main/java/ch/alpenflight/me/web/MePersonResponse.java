package ch.alpenflight.me.web;

import ch.alpenflight.persons.application.SelfContactView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire response for {@code GET /api/v1/me/person} — the caller's own editable
 * contact / address shape (J-4 T-18), so the Personal tab (T-07) hydrates with
 * real values instead of rendering empty.
 *
 * <p>Carries the editable contact / address fields (the
 * {@link MePersonUpdateRequest} field set — GET returns it, PATCH replaces it)
 * PLUS the read-only name fields (firstName / lastName / midName / companyName)
 * so the tab can display them. Rename stays admin-only, so the name fields are
 * display-only on this surface and are NOT on the PATCH request.
 */
@Schema(description = "Caller's own Person contact / address fields (read shape for the Personal tab).")
record MePersonResponse(
        // Read-only identity (admin-owned rename) — display only.
        String firstName,
        String lastName,
        @Nullable String midName,
        @Nullable String companyName,
        // Editable contact / address fields (mirror MePersonUpdateRequest).
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String zip,
        @Nullable String city,
        @Nullable String region,
        @Nullable UUID countryId,
        @Nullable String privatePhone,
        @Nullable String mobilePhone,
        @Nullable String businessPhone,
        @Nullable String faxNumber,
        @Nullable String emailPrivate,
        @Nullable String emailBusiness,
        boolean preferMailToBusinessMail,
        @Nullable LocalDate birthday) {

    static MePersonResponse from(SelfContactView v) {
        return new MePersonResponse(
                v.firstName(),
                v.lastName(),
                v.midName(),
                v.companyName(),
                v.addressLine1(),
                v.addressLine2(),
                v.zip(),
                v.city(),
                v.region(),
                v.countryId(),
                v.privatePhone(),
                v.mobilePhone(),
                v.businessPhone(),
                v.faxNumber(),
                v.emailPrivate(),
                v.emailBusiness(),
                v.preferMailToBusinessMail(),
                v.birthday());
    }
}
