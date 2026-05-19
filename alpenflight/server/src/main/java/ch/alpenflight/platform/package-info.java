/**
 * Shared cross-cutting technical kernel — typed IDs, security plumbing,
 * tenancy resolver, OpenAPI config. Declared as an {@link
 * org.springframework.modulith.ApplicationModule#type() OPEN} Spring
 * Modulith module per ADR 0023 § "shared platform exception": every
 * bounded-context module may import {@code platform.*} sub-packages
 * (id, security, tenancy, …) without going through a named interface.
 *
 * <p>The OPEN type is the deliberate shared-kernel exception. Business
 * modules ({@code clubs}, future {@code locations}, {@code flights}, …)
 * remain closed by default; cross-business-module access still goes
 * through Spring Modulith's published API.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.platform;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
