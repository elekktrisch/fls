package ch.alpenflight.locations.domain;

import ch.alpenflight.platform.id.InOutboundPointId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate-internal child of {@link Location}. Represents an inbound or
 * outbound routing waypoint at an airfield. Mapped to V3
 * {@code t_inoutbound_point}; the row is owned by its parent Location and
 * lifecycle-bound via {@code @OneToMany(cascade=ALL, orphanRemoval=true)}.
 *
 * <p>No top-level CRUD endpoint exists for IOPs: per S-049 they are managed
 * solely through the Location edit form. Mutators are package-private —
 * only {@link Location} drives them.
 */
@Entity
@Table(name = "t_inoutbound_point")
public class InOutboundPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    /** Owning aggregate root; populated by Hibernate via {@code @ManyToOne}. */
    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    @SuppressWarnings("UnusedVariable")
    private @Nullable Location location;

    @Column(nullable = false, length = 100)
    private String pointName = "";

    @Column(length = 50)
    private @Nullable String pointType;

    @Column(length = 50)
    private @Nullable String direction;

    @Column
    private @Nullable String description;

    protected InOutboundPoint() {
        // JPA.
    }

    static InOutboundPoint create(String pointName,
                                  @Nullable String pointType,
                                  @Nullable String direction,
                                  @Nullable String description) {
        InOutboundPoint p = new InOutboundPoint();
        p.assignName(pointName);
        p.pointType = blankToNull(pointType);
        p.direction = blankToNull(direction);
        p.description = blankToNull(description);
        return p;
    }

    void attachTo(Location parent) {
        this.location = parent;
    }

    private void assignName(String newName) {
        String trimmed = newName == null ? "" : newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("InOutboundPoint name must not be blank");
        }
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("InOutboundPoint name exceeds 100 characters");
        }
        this.pointName = trimmed;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public @Nullable InOutboundPointId getId() {
        return InOutboundPointId.ofNullable(id);
    }

    public String getPointName() {
        return pointName;
    }

    public @Nullable String getPointType() {
        return pointType;
    }

    public @Nullable String getDirection() {
        return direction;
    }

    public @Nullable String getDescription() {
        return description;
    }
}
