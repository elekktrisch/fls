package ch.fls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Catches DI / @Configuration misconfig on every PR. */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Test
    void contextLoads() {
        // assertion-less: failure surfaces as an exception in @SpringBootTest setup.
    }
}
