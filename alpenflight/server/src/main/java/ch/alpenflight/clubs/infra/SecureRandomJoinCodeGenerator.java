package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.JoinCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Spring-managed {@link JoinCodeGenerator}. Delegates to the domain's
 * {@code SecureRandom}-backed default — the bean exists so the rotation path
 * can inject the port (ADR 0023) rather than reach for a static.
 */
@Component
public class SecureRandomJoinCodeGenerator implements JoinCodeGenerator {

    private final JoinCodeGenerator delegate = JoinCodeGenerator.secureRandom();

    @Override
    public String generate() {
        return delegate.generate();
    }
}
