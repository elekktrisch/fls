package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.Person;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SelfContactView(
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
