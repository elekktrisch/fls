package ch.alpenflight.platform.tenancy;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

/**
 * Registers {@link TenantInsertGuard} into Hibernate's {@code PRE_INSERT}
 * chain during {@code SessionFactory} boot. Discovered via the
 * {@code META-INF/services/org.hibernate.integrator.spi.Integrator}
 * resource so it runs before any session opens; a Spring-managed
 * {@code @PostConstruct} hook is too late (the chain caches per persister
 * during boot, and the appended listener was silently never invoked).
 */
public final class TenantInsertGuardIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata,
                          BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry()
                .getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.PRE_INSERT, TenantInsertGuard.INSTANCE);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory,
                             SessionFactoryServiceRegistry serviceRegistry) {
        // no-op
    }
}
