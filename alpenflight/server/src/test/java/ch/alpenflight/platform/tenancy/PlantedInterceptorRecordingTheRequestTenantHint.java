package ch.alpenflight.platform.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;

public class PlantedInterceptorRecordingTheRequestTenantHint implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        RequestTenantHint.recordIfHttp(UUID.fromString(request.getHeader("X-Act-As-Club")));
        return true;
    }
}
