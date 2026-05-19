/**
 * Reference-data HTTP adapter. Read-only GET controllers serving the
 * system-global catalogs. Per ADR 0023 this package depends on
 * {@code referencedata.application} and Spring web; never on
 * {@code referencedata.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.referencedata.web;
