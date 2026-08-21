package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.platform.tenancy.PlantedInterceptorRecordingTheRequestTenantHint;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ImpersonationHttpEntryPointGuardRedsOnEveryPlantedViolationTest {

    private static final JavaClasses PLANTED_HTTP_ENTRY_POINTS =
            new ClassFileImporter().importPackages("impersonationentrypointplants");

    private static final JavaClasses PLANTED_TENANCY_PACKAGE_INTERCEPTOR =
            new ClassFileImporter()
                    .importClasses(PlantedInterceptorRecordingTheRequestTenantHint.class);

    @Test
    void input_class_annotation_declared_for_a_method_parameter() {
        assertThatThrownBy(() -> ImpersonationHttpEntryPointGuardTest
                .no_annotation_may_be_declared_for_a_method_parameter
                .check(PLANTED_HTTP_ENTRY_POINTS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedTenantOverrideParameterAnnotation")
                .hasMessageContaining("ElementType.PARAMETER");
    }

    @Test
    void input_class_handler_binding_a_club_identifier_without_own_club_authorization() {
        assertThatThrownBy(() -> ImpersonationHttpEntryPointGuardTest
                .http_handlers_binding_a_club_identifier_must_authorize_the_callers_own_club
                .check(PLANTED_HTTP_ENTRY_POINTS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("listLocationsOfAnyClub")
                .hasMessageContaining("@tenant.isOwnClub");
    }

    @Test
    void input_class_controller_calling_the_tenant_switch() {
        assertThatThrownBy(() -> ImpersonationHttpEntryPointGuardTest
                .controllers_must_not_switch_the_effective_tenant
                .check(PLANTED_HTTP_ENTRY_POINTS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedControllerCallingTenantsRunAs")
                .hasMessageContaining("Tenants.runAs");
    }

    @Test
    void input_class_request_interceptor_calling_the_tenant_switch() {
        assertThatThrownBy(() -> ImpersonationHttpEntryPointGuardTest
                .request_interception_components_must_not_switch_the_effective_tenant
                .check(PLANTED_HTTP_ENTRY_POINTS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedHandlerInterceptorCallingTenantsRunAs")
                .hasMessageContaining("Tenants.runAs");
    }

    @Test
    void input_class_request_interceptor_recording_the_request_tenant_hint_from_inside_the_tenancy_package() {
        assertThatThrownBy(() -> ImpersonationHttpEntryPointGuardTest
                .request_interception_components_must_not_switch_the_effective_tenant
                .check(PLANTED_TENANCY_PACKAGE_INTERCEPTOR))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedInterceptorRecordingTheRequestTenantHint")
                .hasMessageContaining("RequestTenantHint.recordIfHttp");
    }
}
