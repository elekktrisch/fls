package ch.alpenflight.publicregistration.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The discovery-flight submission the web-layer ITs post, as the JSON a browser
 * sends rather than as the server's own request record — a rename or a
 * restructured payload has to break these tests, not silently follow them.
 */
final class DiscoverySubmissions {

    static final String FIRSTNAME = "Rosa";
    static final String LASTNAME = "Renggli";

    private DiscoverySubmissions() {}

    static Map<String, Object> body(LocalDate selectedDay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registrant", registrant());
        body.put("selectedDay", selectedDay.toString());
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
