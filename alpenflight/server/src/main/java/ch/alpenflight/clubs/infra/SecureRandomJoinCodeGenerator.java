package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.JoinCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class SecureRandomJoinCodeGenerator implements JoinCodeGenerator {

    private final JoinCodeGenerator delegate = JoinCodeGenerator.secureRandom();

    @Override
    public String generate() {
        return delegate.generate();
    }
}
