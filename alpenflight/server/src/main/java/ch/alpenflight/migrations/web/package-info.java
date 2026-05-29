/**
 * REST surface for the S-140 keypair handshake.
 *
 * <p>{@code POST /api/v1/migrations/handshake} mints a fresh keypair;
 * {@code GET /api/v1/migrations/handshake/current} restores the caller's
 * in-flight row for the SPA mount path.
 *
 * <p>Per ADR 0023 this layer depends only on {@code migrations.application}
 * (the use-case service + response view) and {@code migrations.domain}
 * (the exception types translated to HTTP status codes).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migrations.web;
