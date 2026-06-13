package ch.alpenflight.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Shared soft-delete lifecycle for aggregate roots whose deletion semantics
 * are "stamp {@code deleted_on} once, record the actor, never hard-delete"
 * (the partial-unique {@code WHERE deleted_on IS NULL} pattern every
 * tenant-scoped catalog table uses). Carries the two soft-delete columns and
 * the idempotent {@link #softDelete(UUID, Clock)} transition so the
 * byte-identical block no longer has to be re-typed on each aggregate.
 *
 * <p><strong>Scope — only the boundary-clean part.</strong> This base owns
 * exactly the lifecycle state that references nothing outside
 * {@code java.time} / {@code java.util}: it is mapped state + one pure method,
 * the textbook {@code @MappedSuperclass} case. It deliberately does NOT carry
 * the per-aggregate {@code @DomainEvents} saved-event hook — that one-liner
 * looks identical but each aggregate must return its OWN module-local event
 * type ({@code AircraftSaved}, {@code LocationSaved}, {@code FlightTypeSaved},
 * …), so lifting it here would force this shared-kernel class to import every
 * bounded context's event — an aggregate-boundary / Spring Modulith violation.
 * The saved-event method therefore stays on each aggregate (ADR 0028).
 *
 * <p>Used only by aggregates whose soft-delete signature is exactly
 * {@code softDelete(userId, clock)} with no extra invariant or cascade:
 * {@code Aircraft}, {@code Location}, {@code FlightType}. {@code Flight}
 * (cascades to crew, {@code Instant}-stamped) and {@code Person} (refuses
 * across active cross-tenant memberships, cascades to {@code PersonClub})
 * keep their own divergent {@code softDelete} and do NOT extend this — their
 * behavior is not the shared shape.
 *
 * <p>No {@code @TenantId} here: tenancy is per-aggregate (some carry it on a
 * {@code clubId} column, {@code Aircraft} is cross-tenant with no discriminator).
 * Mapping a tenant column into a shared base would over-couple the catalog.
 */
@MappedSuperclass
public abstract class SoftDeletableAggregate {

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @PersistedAuditActor
    @SuppressWarnings({"UnusedVariable", "FieldCanBeLocal"})
    private @Nullable UUID deletedByUserId;

    /**
     * Soft-deletes this aggregate. Idempotent: a second call on an
     * already-deleted aggregate is a no-op and preserves the original
     * timestamp + actor (the legacy soft-delete contract).
     *
     * @param userId the principal effecting the delete; persisted to
     *     {@code deleted_by_user_id} for the forensic trail (never read back
     *     through the aggregate API).
     * @param clock  the clock to stamp {@code deleted_on} from.
     */
    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }
}
