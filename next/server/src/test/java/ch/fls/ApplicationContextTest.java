package ch.fls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Catches DI / @Configuration misconfig on every PR. */
@SpringBootTest
class ApplicationContextTest {

    @Test
    void contextLoads() {
        // assertion-less: failure surfaces as an exception in @SpringBootTest setup.
    }
}
