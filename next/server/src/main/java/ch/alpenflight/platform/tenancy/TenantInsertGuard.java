package ch.alpenflight.platform.tenancy;

import java.lang.reflect.Field;
import org.hibernate.annotations.TenantId;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the insert-poisoning hole the {@link ClubTenantIdentifierResolver}
 * leaves open: when the resolver returns {@link ClubTenantIdentifierResolver#NO_TENANT}
 * Hibernate would otherwise persist a tenant-scoped row with {@code club_id}
 * set to the nil UUID. That row would survive in the database forever,
 * undetectable by tenant-scoped queries (which filter on a real
 * {@code club_id}) and only surfacing under a {@code SELECT * FROM …} audit.
 *
 * <p>Wired into Hibernate's {@code PRE_INSERT} event chain via
 * {@link TenantInsertGuardIntegrator} (META-INF/services registration so the
 * listener is attached during {@code SessionFactory} boot). The listener
 * calls the resolver directly: Hibernate populates {@code @TenantId} columns
 * in the entity state slot AFTER {@code PRE_INSERT} fires, so reading the
 * state array sees {@code null}; the resolver is the only source of truth
 * available at this point in the lifecycle.
 *
 * <p>The {@link CurrentTenantIdentifierResolver} reference is injected by
 * Spring via {@link #install(CurrentTenantIdentifierResolver)} during context
 * startup; until then (very early boot, before Spring registers the bean)
 * the listener short-circuits to a no-op, which is safe because no inserts
 * fire before Spring is fully wired.
 *
 * <p>Domain-level guard per ADR 0022 directive 2 — no DB CHECK.
 */
public final class TenantInsertGuard implements PreInsertEventListener {

    static final TenantInsertGuard INSTANCE = new TenantInsertGuard();

    private static final Logger LOG = LoggerFactory.getLogger(TenantInsertGuard.class);

    private @Nullable CurrentTenantIdentifierResolver<?> resolver;

    private TenantInsertGuard() {}

    public static void install(CurrentTenantIdentifierResolver<?> resolver) {
        INSTANCE.resolver = resolver;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        CurrentTenantIdentifierResolver<?> r = resolver;
        if (r == null) {
            return false;
        }
        Object entity = event.getEntity();
        if (!hasTenantId(entity.getClass())) {
            return false;
        }
        Object resolved = r.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(resolved)) {
            EntityPersister persister = event.getPersister();
            LOG.warn("rejecting insert {} with nil @TenantId — no tenant context resolved",
                    persister.getEntityName());
            throw new MissingTenantContextException(
                    "refusing to insert %s with @TenantId=%s — no tenant context resolved"
                            .formatted(persister.getEntityName(), ClubTenantIdentifierResolver.NO_TENANT));
        }
        return false;
    }

    private static boolean hasTenantId(Class<?> type) {
        for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.isAnnotationPresent(TenantId.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
