package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.Person;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read projection of a Person's editable contact / address shape — returned by
 * the caller-scoped {@code GET /api/v1/me/person} (J-4 T-18) so the Personal
 * tab (T-07) hydrates with the caller's real contact / address values instead
 * of rendering empty.
 *
 * <p>Carries the contact / address fields {@link Person#updateContact} can
 * mutate (the {@link SelfContactUpdate} field set) PLUS the read-only name
 * fields (firstname / lastname / midname / companyName) so the tab can display
 * them — rename stays admin-only, so those are display-only, never editable on
 * this surface.
 *
 * <p>Lean + Keycloak-free, like {@link SelfLicencesView}: it avoids the
 * cross-tenant membership-count + member-state lookups {@code PersonsService.toResponse}
 * triggers on the self-edit hot path.
 */
public record SelfContactView(
        // Read-only identity (admin-owned rename) — display only.
        String firstName,
        String lastName,
        @Nullable String midName,
        @Nullable String companyName,
        // Editable contact / address fields.
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

    public static SelfContactView of(Person p) {
        return new SelfContactView(
                p.getFirstname(),
                p.getLastname(),
                p.getMidname(),
                p.getCompanyName(),
                p.getAddressLine1(),
                p.getAddressLine2(),
                p.getZip(),
                p.getCity(),
                p.getRegion(),
                p.getCountryId(),
                p.getPrivatePhone(),
                p.getMobilePhone(),
                p.getBusinessPhone(),
                p.getFaxNumber(),
                p.getEmailPrivate(),
                p.getEmailBusiness(),
                p.isPreferMailToBusinessMail(),
                p.getBirthday());
    }
}
