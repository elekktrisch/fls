package ch.alpenflight.tenancy.sandbox.domain;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_demo_seat")
public class DemoSeat {

    public enum LeaseState {
        FREE,
        LEASED
    }

    @Id
    @UuidV7
    private @Nullable UUID id;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private int seatNumber;

    @Column(name = "club_id", nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "keycloak_username", nullable = false, updatable = false, length = 64)
    private String keycloakUsername = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "lease_state", nullable = false, length = 16)
    private LeaseState leaseState = LeaseState.FREE;

    @Column(name = "lease_holder_key", columnDefinition = "text")
    private @Nullable String leaseHolderKey;

    @Column(name = "lease_expires_at")
    private @Nullable Instant leaseExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_on", nullable = false, updatable = false)
    private @Nullable Instant createdOn;

    @Column(name = "modified_on", nullable = false)
    private @Nullable Instant modifiedOn;

    protected DemoSeat() {
    }

    public void leaseTo(String visitorAddress,
                        Instant now,
                        Duration idlePeriodBeforeTheLeaseExpires) {
        String address = requireAnAddress(visitorAddress);
        requireAPositiveIdlePeriod(idlePeriodBeforeTheLeaseExpires);
        if (this.leaseState != LeaseState.FREE) {
            throw new DemoSeatIsNotFreeException(this.seatNumber, this.leaseState);
        }
        this.leaseState = LeaseState.LEASED;
        this.leaseHolderKey = address;
        this.leaseExpiresAt = now.plus(idlePeriodBeforeTheLeaseExpires);
        this.modifiedOn = now;
    }

    public void returnToPool(Instant now) {
        this.leaseState = LeaseState.FREE;
        this.leaseHolderKey = null;
        this.leaseExpiresAt = null;
        this.modifiedOn = now;
    }

    public boolean isFree() {
        return this.leaseState == LeaseState.FREE;
    }

    public boolean holdsALiveLeaseAt(Instant now) {
        return this.leaseState == LeaseState.LEASED
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now);
    }

    public boolean holdsALiveLeaseFor(String visitorAddress, Instant now) {
        return holdsALiveLeaseAt(now) && visitorAddress.equals(this.leaseHolderKey);
    }

    public boolean isReclaimableAt(Instant now) {
        return this.leaseState == LeaseState.LEASED && !holdsALiveLeaseAt(now);
    }

    private static String requireAnAddress(String visitorAddress) {
        String trimmed = visitorAddress == null ? "" : visitorAddress.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("visitorAddress must not be blank");
        }
        return trimmed;
    }

    private static void requireAPositiveIdlePeriod(Duration idlePeriodBeforeTheLeaseExpires) {
        if (idlePeriodBeforeTheLeaseExpires == null
                || idlePeriodBeforeTheLeaseExpires.isZero()
                || idlePeriodBeforeTheLeaseExpires.isNegative()) {
            throw new IllegalArgumentException(
                    "the idle period before the lease expires must be positive, was "
                            + idlePeriodBeforeTheLeaseExpires);
        }
    }

    public UUID getId() {
        return requireLoaded(this.id, "id");
    }

    public int getSeatNumber() {
        return this.seatNumber;
    }

    public ClubId getClubId() {
        return new ClubId(requireLoaded(this.clubId, "clubId"));
    }

    public String getKeycloakUsername() {
        return this.keycloakUsername;
    }

    public LeaseState getLeaseState() {
        return this.leaseState;
    }

    public @Nullable String getLeaseHolderKey() {
        return this.leaseHolderKey;
    }

    public @Nullable Instant getLeaseExpiresAt() {
        return this.leaseExpiresAt;
    }

    public long getVersion() {
        return this.version;
    }

    private <T> T requireLoaded(@Nullable T value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException(
                    "DemoSeat." + fieldName + " is null; the pool row is created by Flyway and "
                            + "every field is set before the aggregate leaves the repository");
        }
        return value;
    }
}
