package ch.alpenflight.buildgates.versioncolumngate.fixtures.missingversion;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class EntityWithoutVersion {

    @Id
    private UUID id;
}
