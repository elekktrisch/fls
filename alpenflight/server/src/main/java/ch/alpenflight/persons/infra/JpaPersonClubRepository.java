package ch.alpenflight.persons.infra;

import ch.alpenflight.persons.domain.PersonClub;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPersonClubRepository extends JpaRepository<PersonClub, UUID> {
}
