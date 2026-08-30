package ch.alpenflight.buildgates.versioncolumngate.fixtures.inheritedversion;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class EntityWithInheritedVersion extends AbstractVersionedFixture {

    @Id
    private UUID id;
}
