package ch.alpenflight.publicregistration.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The submissions the web-layer ITs post, as the JSON a browser sends rather
 * than as the server's own request records — a rename or a restructured payload
 * has to break these tests, not silently follow them.
 *
 * <p>Both bodies carry the same registrant, which is the contract under test:
 * the scenic form is the discovery form minus the day selection.
 */
final class PublicSubmissions {

    static final String FIRSTNAME = "Rosa";
    static final String LASTNAME = "Renggli";

    private PublicSubmissions() {}

    static Map<String, Object> discoveryBody(LocalDate selectedDay) {
        Map<String, Object> body = scenicBody();
        body.put("selectedDay", selectedDay.toString());
        return body;
    }

    static Map<String, Object> scenicBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registrant", registrant());
        return body;
    }

    static Map<String, Object> registrant() {
        Map<String, Object> registrant = new LinkedHashMap<>();
        registrant.put("firstname", FIRSTNAME);
        registrant.put("lastname", LASTNAME);
        registrant.put("addressLine1", "Flugplatzstrasse 7");
        registrant.put("zip", "6060");
        registrant.put("city", "Sarnen");
        registrant.put("mobilePhone", "079 555 66 77");
        registrant.put("privateEmail", "rosa.renggli@example.ch");
        registrant.put("invoiceAddressIsSame", true);
        registrant.put("sendCouponToInvoiceAddress", false);
        return registrant;
    }
}
