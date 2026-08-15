package ch.alpenflight.platform.scheduling;

import org.jspecify.annotations.Nullable;

public interface BusinessJob {

    @Nullable Object runOnce();
}
