/**
 * Spring Data JPA adapters for the reference-data domain ports. Per
 * ADR 0023 nothing in {@code referencedata.web} or
 * {@code referencedata.application} may import from this package; the
 * adapter is reached only through the domain port.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.referencedata.infra;
