package ch.alpenflight.referencedata.infra;

import ch.alpenflight.referencedata.domain.ClubState;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaClubStateRepository extends JpaRepository<ClubState, UUID>, ClubStateRepository {

    @Override
    @Query("SELECT s FROM ClubState s ORDER BY s.name")
    List<ClubState> findAllOrdered();
}
