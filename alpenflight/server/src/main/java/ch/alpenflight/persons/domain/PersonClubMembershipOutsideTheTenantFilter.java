package ch.alpenflight.persons.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

@Entity
@Immutable
@Table(name = "t_person_club")
public class PersonClubMembershipOutsideTheTenantFilter {

    @Id
    @Column(name = "id", insertable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "person_id", insertable = false, updatable = false)
    private @Nullable UUID personId;

    @Column(name = "club_id", insertable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "deleted_on", insertable = false, updatable = false)
    private @Nullable Instant deletedOn;

    protected PersonClubMembershipOutsideTheTenantFilter() {
    }

    public @Nullable UUID getPersonId() {
        return personId;
    }

    public @Nullable UUID getClubId() {
        return clubId;
    }
}
