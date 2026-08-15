package ch.alpenflight.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@MappedSuperclass
public abstract class SoftDeletableAggregate {

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @PersistedAuditActor
    @SuppressWarnings({"UnusedVariable", "FieldCanBeLocal"})
    private @Nullable UUID deletedByUserId;

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
