package impersonationentrypointplants;

import ch.alpenflight.platform.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;

public final class ImpersonationEntryPointPlants {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface PlantedTenantOverrideParameterAnnotation {
    }

    @RestController
    @RequestMapping("/api/v1/admin/locations")
    public static class PlantedAdminLocationsControllerTakingTheClubFromThePath {

        @GetMapping("/{clubId}")
        @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
        public List<String> listLocationsOfAnyClub(
                @PlantedTenantOverrideParameterAnnotation @PathVariable UUID clubId) {
            return List.of(clubId.toString());
        }
    }

    @RestController
    public static class PlantedControllerCallingTenantsRunAs {

        @PostMapping("/api/v1/planted/act-as")
        public String actAsAnotherClub(@RequestBody UUID clubId) {
            return Tenants.runAs(clubId, () -> "acted as " + clubId);
        }
    }

    public static class PlantedHandlerInterceptorCallingTenantsRunAs implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            Tenants.runAs(UUID.fromString(request.getHeader("X-Act-As-Club")), () -> { });
            return true;
        }
    }

    private ImpersonationEntryPointPlants() {
    }
}
