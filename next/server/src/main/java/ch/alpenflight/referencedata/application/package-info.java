/**
 * Reference-data use-case layer. Read-only services + listitem DTOs +
 * domain-to-DTO mapper. Per ADR 0023, depends on
 * {@code referencedata.domain} only; never on {@code referencedata.web}
 * or {@code referencedata.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.referencedata.application;
