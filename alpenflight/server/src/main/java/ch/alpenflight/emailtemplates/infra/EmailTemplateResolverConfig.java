package ch.alpenflight.emailtemplates.infra;

import ch.alpenflight.platform.mail.TemplatedMailService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

@Configuration
class EmailTemplateResolverConfig {

    private static final String EMAIL_TEMPLATE_PATTERN =
            TemplatedMailService.EMAIL_TEMPLATE_PREFIX + "*";

    @Bean
    BeanPostProcessor emailTemplatesNonCacheable() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SpringResourceTemplateResolver resolver) {
                    Set<String> patterns = new LinkedHashSet<>(resolver.getNonCacheablePatterns());
                    patterns.add(EMAIL_TEMPLATE_PATTERN);
                    resolver.setNonCacheablePatterns(patterns);
                }
                return bean;
            }
        };
    }
}
