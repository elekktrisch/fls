package ch.alpenflight.server.testsupport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PublicSubmissions {

    public static final String FIRSTNAME = "Rosa";
    public static final String LASTNAME = "Renggli";
    public static final String INVOICE_FIRSTNAME = "Beat";
    public static final String INVOICE_LASTNAME = "Bezahler";

    private PublicSubmissions() {}

    public static Map<String, Object> discoveryBody(LocalDate selectedDay) {
        Map<String, Object> body = scenicBody();
        body.put("selectedDay", selectedDay.toString());
        return body;
    }

    public static Map<String, Object> scenicBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registrant", registrant());
        return body;
    }

    public static Map<String, Object> registrant() {
        Map<String, Object> registrant = new LinkedHashMap<>();
        registrant.put("firstname", FIRSTNAME);
        registrant.put("lastname", LASTNAME);
        registrant.put("addressLine1", "Flugplatzstrasse 7");
        registrant.put("zip", "6060");
        registrant.put("city", "Sarnen");
        registrant.put("mobilePhone", "079 555 66 77");
        registrant.put("privateEmail", "rosa.renggli@example.ch");
        registrant.put("invoiceAddressIsSame", true);
        return registrant;
    }

    public static Map<String, Object> registrantWithDifferingInvoiceAddress(
            boolean sendCouponToInvoiceAddress) {
        Map<String, Object> registrant = registrant();
        registrant.put("invoiceAddressIsSame", false);
        registrant.put("sendCouponToInvoiceAddress", sendCouponToInvoiceAddress);
        registrant.put("invoiceRecipient", invoiceRecipient());
        return registrant;
    }

    private static Map<String, Object> invoiceRecipient() {
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("firstname", INVOICE_FIRSTNAME);
        invoice.put("lastname", INVOICE_LASTNAME);
        invoice.put("addressLine1", "Buchhaltungsweg 3");
        invoice.put("zip", "6003");
        invoice.put("city", "Luzern");
        invoice.put("notificationEmail", "beat.bezahler@example.ch");
        return invoice;
    }
}
