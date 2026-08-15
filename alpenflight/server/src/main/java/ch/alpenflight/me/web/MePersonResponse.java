package ch.alpenflight.me.web;

import ch.alpenflight.persons.application.SelfContactView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Caller's own Person contact / address fields (read shape for the Personal tab).")
record MePersonResponse(
        String firstName,
        String lastName,
        @Nullable String midName,
        @Nullable String companyName,
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
