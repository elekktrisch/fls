package ch.alpenflight.buildgates.nocrossslicereachgate.fixtures.someslice.application;

import ch.alpenflight.buildgates.nocrossslicereachgate.fixtures.someslice.domain.SomeAggregate;

public class InsiderService {

    private final SomeAggregate someAggregate;

    public InsiderService(SomeAggregate someAggregate) {
        this.someAggregate = someAggregate;
    }
}
