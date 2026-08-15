package ch.alpenflight.publicregistration.application;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PublicRegistrantDetails(
        String firstname,
        String lastname,
        String addressLine1,
        String zip,
        String city,
        @Nullable UUID countryId,
        @Nullable String privatePhone,
        @Nullable String businessPhone,
        @Nullable String mobilePhone,
        @Nullable String privateEmail,
        @Nullable String remarks,
        boolean invoiceAddressIsSame,
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean sendCouponToInvoiceAddress,
        @Nullable InvoiceRecipient invoiceRecipient) {

    public PublicRegistrantDetails {
        firstname = required(firstname, "firstname");
        lastname = required(lastname, "lastname");
        addressLine1 = required(addressLine1, "addressLine1");
        zip = required(zip, "zip");
        city = required(city, "city");
        privatePhone = trimToNull(privatePhone);
        businessPhone = trimToNull(businessPhone);
        mobilePhone = trimToNull(mobilePhone);
        privateEmail = trimToNull(privateEmail);
        remarks = trimToNull(remarks);
        if (mobilePhone == null && privateEmail == null) {
            throw new PublicRegistrationInvalidException(
                    "at least one of mobilePhone / privateEmail is required");
        }
        if (invoiceAddressIsSame) {
            invoiceRecipient = null;
            sendCouponToInvoiceAddress = false;
        } else if (invoiceRecipient == null) {
            throw new PublicRegistrationInvalidException(
                    "invoiceRecipient is required when the invoice address differs");
        }
    }

    public record InvoiceRecipient(
            String firstname,
            String lastname,
            String addressLine1,
            String zip,
            String city,
            @Nullable UUID countryId,
            String notificationEmail) {

        public InvoiceRecipient {
            firstname = required(firstname, "invoice firstname");
            lastname = required(lastname, "invoice lastname");
            addressLine1 = required(addressLine1, "invoice addressLine1");
            zip = required(zip, "invoice zip");
            city = required(city, "invoice city");
            notificationEmail = required(notificationEmail, "invoice notificationEmail");
        }
    }

    private static String required(@Nullable String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new PublicRegistrationInvalidException(field + " is required");
        }
        return trimmed;
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
