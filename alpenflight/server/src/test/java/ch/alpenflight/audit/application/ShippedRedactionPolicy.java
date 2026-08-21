package ch.alpenflight.audit.application;

import java.io.IOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

final class ShippedRedactionPolicy {

    private ShippedRedactionPolicy() {
    }

    static AuditRedactionProperties properties() throws IOException {
        return Binder.get(environment())
                .bind(AuditRedactionConfigStartupGuard.CONFIG_ROOT, AuditRedactionProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml declares no audit.redaction"));
    }

    static StandardEnvironment environment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addFirst(source);
        }
        return environment;
    }
}
