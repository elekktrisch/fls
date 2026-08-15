package ch.alpenflight.accounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

@Embeddable
public record DeliveryRecipient(
        @Column(name = "recipient_name", length = 250)
        @Nullable String name,

        @Column(name = "recipient_firstname", length = 100)
        @Nullable String firstname,

        @Column(name = "recipient_lastname", length = 100)
        @Nullable String lastname,

        @Column(name = "recipient_address_line1", length = 200)
        @Nullable String addressLine1,

        @Column(name = "recipient_address_line2", length = 200)
        @Nullable String addressLine2,

        @Column(name = "recipient_zip_code", length = 10)
        @Nullable String zipCode,

        @Column(name = "recipient_city", length = 100)
        @Nullable String city,

        @Column(name = "recipient_country_name", length = 100)
        @Nullable String countryName,

        @Column(name = "recipient_person_club_member_number", length = 20)
        @Nullable String personClubMemberNumber) {

    public static DeliveryRecipient empty() {
        return new DeliveryRecipient(null, null, null, null, null, null, null, null, null);
    }
}
