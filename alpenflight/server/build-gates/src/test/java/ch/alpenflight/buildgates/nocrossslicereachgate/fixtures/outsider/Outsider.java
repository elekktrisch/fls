package ch.alpenflight.buildgates.nocrossslicereachgate.fixtures.outsider;

import ch.alpenflight.buildgates.nocrossslicereachgate.fixtures.someslice.domain.SomeAggregate;

public class Outsider {

    private final SomeAggregate someAggregate;

    public Outsider(SomeAggregate someAggregate) {
        this.someAggregate = someAggregate;
    }
}
