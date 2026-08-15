package ch.alpenflight.platform.tenancy;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class TenancyWebMvcConfig implements WebMvcConfigurer {

    private final AuditTargetTenantInterceptor interceptor;

    TenancyWebMvcConfig(AuditTargetTenantInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
