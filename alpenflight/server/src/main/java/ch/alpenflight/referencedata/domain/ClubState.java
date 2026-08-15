package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.ClubStateId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;


@Entity
@Table(name = "t_club_state")
public class ClubState {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code = "";

    @Column(name = "name", nullable = false, length = 50)
    private String name = "";

    protected ClubState() {
    }

    public @Nullable ClubStateId getId() {
        return ClubStateId.ofNullable(id);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
