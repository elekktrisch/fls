package ch.alpenflight.clubs.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deliberate ADR 0023 violation: a class in a {@code ..domain..} package
 * imports Jackson — exactly what Rule 1 of {@link
 * ch.alpenflight.arch.LayeringRulesTest} forbids. Lives only in the
 * {@code archDemo} source set so the production {@code ./gradlew test}
 * never sees it; {@code ./gradlew verifyArchUnitFailsOnViolation}
 * scans this and asserts Rule 1 fires.
 */
public class JacksonLeak {

    @SuppressWarnings("unused") // Field exists solely to carry the Jackson annotation that ArchUnit scans.
    @JsonProperty("name")
    private String name = "";
}
