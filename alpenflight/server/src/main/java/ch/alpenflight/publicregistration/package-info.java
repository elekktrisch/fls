/**
 * Public flight-experience registration — the application's only anonymous
 * write surface (S-025). A prospective customer books a discovery or scenic
 * flight at one club without an account, so there is no principal and no
 * {@code clubId} claim: the target tenant comes from the URL.
 *
 * <h2>Tenant-from-URL</h2>
 *
 * <p>{@link ch.alpenflight.publicregistration.application.PublicClubResolver}
 * turns the slug path segment into a club and checks the
 * {@code public_registration_enabled} allowlist BEFORE any tenant window opens.
 * Resolution failures (404 unknown / 403 closed) therefore run with no tenant
 * scope at all and can write nothing. Only the accepted path enters
 * {@code Tenants.runAs}, and only for the resolved club — see
 * {@link ch.alpenflight.publicregistration.application.PublicRegistrationIntake}.
 * A request interceptor was rejected for this reason: it would scope the whole
 * request, failure paths included.
 *
 * <p>Layered per ADR 0023 into {@code application} (resolver, intake service,
 * transactional writer) and {@code web} (controller + exception handler).
 */
@org.springframework.modulith.ApplicationModule
@org.jspecify.annotations.NullMarked
package ch.alpenflight.publicregistration;
