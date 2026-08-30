package ch.alpenflight.core.club;

import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "club")
public class Club {

    @Id
    @UuidV7
    @ColumnDefault("uuidv7()")
    @Column(updatable = false)
    private UUID id;

    private String name;

    @Version
    private Long version;

    private OffsetDateTime deletedAt;

    protected Club() {
    }

    public Club(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getVersion() {
        return version;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
