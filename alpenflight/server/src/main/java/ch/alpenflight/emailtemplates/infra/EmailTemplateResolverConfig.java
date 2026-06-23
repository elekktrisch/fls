package ch.alpenflight.emailtemplates.infra;

import ch.alpenflight.platform.mail.TemplatedMailService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/**
 * Makes the auto-configured file/classpath resolver treat {@code email/*} as
 * non-cacheable so a DB override takes effect without a redeploy. Thymeleaf
 * caches a resolved template by name across renders; with the default
 * {@code spring.thymeleaf.cache=true}, the first send of an email template would
 * cache the file resolution and mask a later override — so the
 * {@link EmailTemplateDbResolver} chain would never be re-consulted. Excluding
 * the email prefix from the cache keeps the chain live for every send while
 * leaving web-view caching untouched.
 */
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
