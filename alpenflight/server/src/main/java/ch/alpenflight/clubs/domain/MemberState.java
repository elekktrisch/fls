package ch.alpenflight.clubs.domain;

import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_member_state")
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
