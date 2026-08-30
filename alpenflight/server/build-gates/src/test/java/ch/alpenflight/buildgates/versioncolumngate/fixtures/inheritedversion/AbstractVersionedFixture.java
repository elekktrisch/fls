package ch.alpenflight.buildgates.versioncolumngate.fixtures.inheritedversion;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

@MappedSuperclass
public abstract class AbstractVersionedFixture {

    @Version
    private Long version;
}
