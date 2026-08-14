package ch.alpenflight.articles.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class ArticlesTestFixtures {

    private static final AtomicInteger NUMBER_COUNTER = new AtomicInteger(0);

    private ArticlesTestFixtures() {}

    static String uniqueNumber() {
        return "IT_ART_" + NUMBER_COUNTER.incrementAndGet();
    }

    static Map<String, Object> createPayload(String number) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("articleNumber", number);
        n.put("articleName", "Glider hour");
        n.put("articleInfo", "per flight");
        n.put("description", null);
        n.put("isActive", Boolean.TRUE);
        return n;
    }

    static Map<String, Object> updatePayload(String number) {
        Map<String, Object> n = createPayload(number);
        n.put("articleName", "Glider hour (renamed)");
        n.put("articleInfo", "per flight, including landing fee");
        return n;
    }
}
