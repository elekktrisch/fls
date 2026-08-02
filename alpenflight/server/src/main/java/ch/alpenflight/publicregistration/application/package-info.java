/**
 * Public-registration use-case orchestration: slug resolution, the allowlist
 * gate, and the narrow tenant window the accepted submission runs in.
 *
 * <p>Per ADR 0023 this layer depends on {@code clubs.domain} (the aggregate +
 * its repository port) and on {@code platform.tenancy}; it must not depend on
 * {@code publicregistration.web}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.publicregistration.application;
