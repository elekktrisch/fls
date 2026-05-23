package ch.alpenflight.platform.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method's path-variable {@code ClubId} parameter as the
 * "operating tenant" of a sysadmin cross-tenant request. A
 * {@code HandlerInterceptor} publishes the value as a {@link RequestTenantHint}
 * BEFORE controller-argument validation fires, so a 4xx validation rejection
 * still attributes its synthetic audit row to the path-variable target
 * tenant (not the sysadmin's home tenant from the JWT).
 *
 * <p>Without this annotation the hint is only published inside
 * {@link Tenants#runAs}, which only fires after the controller body
 * executes — too late for the failure paths that never reach the body.
 *
 * <p>Apply alongside {@code @PathVariable} on {@code LocationsAdminController}-
 * style admin endpoints. Regular tenant-scoped endpoints don't need it:
 * the operating tenant comes from the JWT and is already on the request
 * via the standard resolver chain.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditTargetTenant {
}
