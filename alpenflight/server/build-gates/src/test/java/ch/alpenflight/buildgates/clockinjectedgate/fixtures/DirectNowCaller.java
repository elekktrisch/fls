package ch.alpenflight.buildgates.clockinjectedgate.fixtures;

import java.time.Instant;

public class DirectNowCaller {

    public Instant capturedNow() {
        return Instant.now();
    }
}
