package ch.alpenflight.accounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

/**
 * The nine frozen recipient snapshot fields on {@link Delivery}, captured at
 * invoice booking and NEVER re-resolved from {@code recipient_person_id} per
 * Swiss OR Art. 957a (10-year retention; DSAR-exempt once Booked). An immutable
 * {@code @Embeddable} value object owned by the aggregate — the V4 columns are a
 * contiguous snapshot family, so they map as one embedded VO rather than nine
 * scattered getters.
 *
 * <p>Every field is nullable: a clean-seed / not-yet-booked delivery may have an
 * empty snapshot, and legacy rows carried partial recipient data.
 */
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

    /** An all-null snapshot — a delivery with no recipient resolved yet. */
    public static DeliveryRecipient empty() {
        return new DeliveryRecipient(null, null, null, null, null, null, null, null, null);
    }
}
