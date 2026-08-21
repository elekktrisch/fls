package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.tenancy.RequestTenantHint;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import ch.alpenflight.platform.tenancy.Tenants;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.servlet.Filter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ImpersonationHttpEntryPointGuardTest {

    static final Set<String>
            HTTP_SURFACES_THAT_MAY_TAKE_THE_CLUB_FROM_THE_REQUEST_REVIEWED_AGAINST_ADR_0008 = Set.of(
                    "ch.alpenflight.clubs.web.ClubsController",
                    "ch.alpenflight.publicregistration.web.PublicRegistrationController");

    static final String AUDIT_FILTER_IS_THE_IN_PROCESS_SEAM_ADR_0008_NAMES_BY_HAND =
            "ch.alpenflight.audit.web.RequestAuditFilter";

    private static final Set<String> HTTP_MAPPING_ANNOTATION_NAMES = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping");

    private static final Set<String> REQUEST_BINDING_ANNOTATION_NAMES = Set.of(
            PathVariable.class.getName(),
            RequestParam.class.getName(),
            RequestHeader.class.getName());

    private static final Pattern CLUB_NAMED_URI_TEMPLATE_VARIABLE =
            Pattern.compile("\\{[^}]*club[^}]*}", Pattern.CASE_INSENSITIVE);

    private static final String OWN_CLUB_AUTHORIZATION_CALL = "isOwnClub";

    private static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            " Residual limit — this guard reads bytecode shape, not intent. It does NOT catch: a tenant "
                    + "selector carried inside a @RequestBody field; a request-bound club id whose "
                    + "parameter has no explicit binding name, no ClubId type and no {club…} URI template; "
                    + "or a tenant switch reached through an intermediate helper class, which "
                    + "TenantsRunAsAllowlistTest covers by allow-listed caller FQN. Extend this guard "
                    + "rather than route around it.";

    @ArchTest
    static final ArchRule no_annotation_may_be_declared_for_a_method_parameter =
            noClasses()
                    .that(describe("are annotation types", (JavaClass c) -> c.isAnnotation()))
                    .should(beDeclarableOnAMethodParameter())
                    .as("ADR 0008 §Amendment S-159: no HTTP path may let an outside caller act as a "
                            + "club. A parameter-level annotation of ours is readable only by request "
                            + "machinery — an interceptor or an argument resolver. That is exactly how "
                            + "the deleted @AuditTargetTenant retargeted the audit tenant of "
                            + "/api/v1/admin/locations/{clubId}. Declare an explicit @Target that excludes "
                            + "ElementType.PARAMETER."
                            + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);

    @ArchTest
    static final ArchRule http_handlers_binding_a_club_identifier_must_authorize_the_callers_own_club =
            methods()
                    .that(describe("are HTTP handlers that bind a club identifier from the request",
                            (JavaMethod m) -> isHttpHandlerBindingAClubIdentifier(m)))
                    .should(authorizeTheCallersOwnClub())
                    .as("ADR 0008 §Amendment S-159 withdrew /api/v1/admin/locations/{clubId}: an endpoint "
                            + "that takes the club from the request must prove the caller belongs to that "
                            + "club through @PreAuthorize(\"… @tenant.isOwnClub(#…) …\"). Two surfaces are "
                            + "reviewed exceptions: the clubs catalog, which S-159 keeps as a cross-cutting "
                            + "resource, and the unauthenticated public-registration intake, where the club "
                            + "publishes its own slug and can close the surface. Adding an FQN to that set "
                            + "asserts the surface never acts on tenant data for an authenticated caller."
                            + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);

    @ArchTest
    static final ArchRule controllers_must_not_switch_the_effective_tenant =
            noClasses()
                    .that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .should(callTheTenantSwitch())
                    .as("ADR 0008 §Amendment S-159: Tenants.runAs is an in-process seam, never an HTTP "
                            + "entry point. A controller that switches tenant IS the impersonation "
                            + "surface, so this rule carries no allow-list. It closes the door "
                            + "TenantsRunAsAllowlistTest leaves open, where one added caller FQN buys an "
                            + "HTTP entry point the switch."
                            + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);

    @ArchTest
    static final ArchRule request_interception_components_must_not_switch_the_effective_tenant =
            noClasses()
                    .that(interceptHttpRequestsOutsideTheSanctionedAuditFilter())
                    .should(callTheTenantSwitch())
                    .as("ADR 0008 §Amendment S-159: the deleted AuditTargetTenantInterceptor was a "
                            + "HandlerInterceptor wired onto /api/v1/** by TenancyWebMvcConfig; it read a "
                            + "path variable and retargeted the tenant. Any HandlerInterceptor, "
                            + "HandlerMethodArgumentResolver, servlet Filter or @ControllerAdvice that "
                            + "reaches the tenant switch rebuilds that surface. RequestAuditFilter is the "
                            + "single reviewed exception, named by hand in the ADR amendment."
                            + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);

    private static ArchCondition<JavaClass> beDeclarableOnAMethodParameter() {
        return new ArchCondition<>("be declarable on a method parameter") {
            @Override
            public void check(JavaClass annotationType, ConditionEvents events) {
                Optional<Target> target = annotationType.tryGetAnnotationOfType(Target.class);
                boolean everyElementTypeIsAllowed = target.isEmpty();
                boolean parameterIsAllowed = everyElementTypeIsAllowed
                        || Arrays.asList(target.get().value()).contains(ElementType.PARAMETER);
                if (!parameterIsAllowed) {
                    return;
                }
                String shape = everyElementTypeIsAllowed
                        ? "declares no @Target, so it may sit on a method parameter"
                        : "declares @Target(… ElementType.PARAMETER …)";
                events.add(SimpleConditionEvent.satisfied(annotationType,
                        annotationType.getName() + " " + shape));
            }
        };
    }

    private static ArchCondition<JavaMethod> authorizeTheCallersOwnClub() {
        return new ArchCondition<>("authorize the caller's own club") {
            @Override
            public void check(JavaMethod handler, ConditionEvents events) {
                boolean ownClubIsProven = preAuthorizeExpressionsOf(handler)
                        .anyMatch(expression -> expression.contains(OWN_CLUB_AUTHORIZATION_CALL));
                boolean surfaceWasReviewedByHand =
                        HTTP_SURFACES_THAT_MAY_TAKE_THE_CLUB_FROM_THE_REQUEST_REVIEWED_AGAINST_ADR_0008
                                .contains(handler.getOwner().getFullName());
                if (ownClubIsProven || surfaceWasReviewedByHand) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(handler,
                        handler.getFullName() + " takes the club from the HTTP request, but no "
                                + "@PreAuthorize on it names @tenant.isOwnClub"));
            }
        };
    }

    private static ArchCondition<JavaClass> callTheTenantSwitch() {
        return new ArchCondition<>("call Tenants.runAs, TenantContextCarrier.set "
                + "or RequestTenantHint.recordIfHttp") {
            @Override
            public void check(JavaClass caller, ConditionEvents events) {
                caller.getMethodCallsFromSelf().stream()
                        .filter(call -> isTenantSwitch(
                                call.getTargetOwner().getFullName(), call.getName()))
                        .forEach(call -> events.add(SimpleConditionEvent.satisfied(caller,
                                caller.getName() + "." + call.getOrigin().getName() + " calls "
                                        + call.getTargetOwner().getSimpleName() + "."
                                        + call.getName())));
            }
        };
    }

    private static boolean isTenantSwitch(String targetOwner, String methodName) {
        if (Tenants.class.getName().equals(targetOwner)) {
            return "runAs".equals(methodName);
        }
        if (TenantContextCarrier.class.getName().equals(targetOwner)) {
            return "set".equals(methodName);
        }
        return RequestTenantHint.class.getName().equals(targetOwner)
                && "recordIfHttp".equals(methodName);
    }

    private static DescribedPredicate<JavaClass> interceptHttpRequestsOutsideTheSanctionedAuditFilter() {
        return describe("intercept HTTP requests and are not the audit filter ADR 0008 sanctions",
                (JavaClass candidate) ->
                        !AUDIT_FILTER_IS_THE_IN_PROCESS_SEAM_ADR_0008_NAMES_BY_HAND
                                .equals(candidate.getFullName())
                                && (candidate.isAssignableTo(HandlerInterceptor.class)
                                        || candidate.isAssignableTo(HandlerMethodArgumentResolver.class)
                                        || candidate.isAssignableTo(Filter.class)
                                        || candidate.isAnnotatedWith(ControllerAdvice.class)));
    }

    private static boolean isHttpHandlerBindingAClubIdentifier(JavaMethod method) {
        if (!ownerIsAController(method) || !carriesAnHttpMapping(method)) {
            return false;
        }
        boolean clubNamedTemplateVariable = mappingTemplatesOf(method)
                .anyMatch(template -> CLUB_NAMED_URI_TEMPLATE_VARIABLE.matcher(template).find());
        return clubNamedTemplateVariable
                || method.getParameters().stream()
                        .anyMatch(ImpersonationHttpEntryPointGuardTest::bindsAClubIdentifier);
    }

    private static boolean ownerIsAController(JavaMethod method) {
        JavaClass owner = method.getOwner();
        return owner.isAnnotatedWith(RestController.class) || owner.isAnnotatedWith(Controller.class);
    }

    private static boolean carriesAnHttpMapping(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> HTTP_MAPPING_ANNOTATION_NAMES.contains(a.getRawType().getName()));
    }

    private static boolean bindsAClubIdentifier(JavaParameter parameter) {
        List<JavaAnnotation<JavaParameter>> requestBindings = parameter.getAnnotations().stream()
                .filter(a -> REQUEST_BINDING_ANNOTATION_NAMES.contains(a.getRawType().getName()))
                .toList();
        if (requestBindings.isEmpty()) {
            return false;
        }
        if (ClubId.class.getName().equals(parameter.getRawType().getName())) {
            return true;
        }
        return requestBindings.stream()
                .flatMap(a -> Stream.concat(
                        stringPropertiesOf(a, "value"), stringPropertiesOf(a, "name")))
                .anyMatch(boundName -> boundName.toLowerCase(Locale.ROOT).contains("club"));
    }

    private static Stream<String> mappingTemplatesOf(JavaMethod method) {
        return Stream.<JavaAnnotation<?>>concat(
                        method.getAnnotations().stream(),
                        method.getOwner().getAnnotations().stream())
                .filter(a -> HTTP_MAPPING_ANNOTATION_NAMES.contains(a.getRawType().getName()))
                .flatMap(a -> Stream.concat(
                        stringPropertiesOf(a, "value"), stringPropertiesOf(a, "path")));
    }

    private static Stream<String> stringPropertiesOf(JavaAnnotation<?> annotation, String property) {
        Object value = annotation.getProperties().get(property);
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(String::valueOf);
        }
        return value == null ? Stream.empty() : Stream.of(String.valueOf(value));
    }

    private static Stream<String> preAuthorizeExpressionsOf(JavaMethod method) {
        return Stream.concat(
                        method.tryGetAnnotationOfType(PreAuthorize.class).stream(),
                        method.getOwner().tryGetAnnotationOfType(PreAuthorize.class).stream())
                .map(PreAuthorize::value);
    }
}
