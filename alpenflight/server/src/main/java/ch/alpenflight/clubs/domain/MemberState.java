package ch.alpenflight.clubs.domain;

import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Per-club lookup row for member statuses (e.g. "Active", "Suspended",
 * "Honorary"). Mapped to V2's {@code member_state} table; the discriminator
 * column {@code club_id} wears {@link TenantId} so Hibernate appends
 * {@code WHERE club_id = ?} to every read driven by the
 * {@link ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver}.
 *
 * <p>Worked example for S-022 — the resolver + {@code @TenantId} contract
 * proves end-to-end on this entity before the per-domain stories (S-049
 * Locations, S-050 Aircraft, …) extend the pattern. The mapping is
 * intentionally minimal (no soft-delete handling, no audit-column getters):
 * the story that owns per-club configuration of member statuses will
 * extend it without reshaping.
 */
@Entity
@Table(name = "member_state")
public class MemberState {

    private static final int MAX_NAME_LENGTH = 50;

    @Id
    @UuidV7
    private @Nullable UUID id;

    @TenantId
    @Column(name = "club_id", nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name = "";

    protected MemberState() {
        // JPA.
    }

    public MemberState(String name) {
        rename(name);
    }

    public void rename(String newName) {
        String trimmed = newName == null ? "" : newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("MemberState name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "MemberState name exceeds %d characters".formatted(MAX_NAME_LENGTH));
        }
        this.name = trimmed;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getClubId() {
        return clubId;
    }

    public String getName() {
        return name;
    }
}
