package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.Delivery;

final class DeliverySweepFactory {

    private DeliverySweepFactory() {}

    static Delivery build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        return DeliveryTestHydrator.bare();
    }
}
