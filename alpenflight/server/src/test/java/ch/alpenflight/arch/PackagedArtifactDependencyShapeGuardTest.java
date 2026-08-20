package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class PackagedArtifactDependencyShapeGuardTest {

    static final String REST_CLIENT_BUILDER_FULL_NAME =
            "org.springframework.web.client.RestClient$Builder";

    private static final Set<String> ANNOTATIONS_THAT_MARK_AN_INJECTION_POINT = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.context.annotation.Bean",
            "jakarta.annotation.Resource");

    static final String WHY_NO_PRODUCTION_CLASS_MAY_INJECT_THE_REST_CLIENT_BUILDER =
            "The packaged boot jar supplies no RestClient.Builder bean. The auto-configuration "
                    + "that creates it is RestClientAutoConfiguration, inside the module "
                    + "spring-boot-restclient. That module reaches the test runtime classpath only, "
                    + "through the testImplementation dependency "
                    + "spring-boot-starter-restclient-test. The main runtime classpath does not "
                    + "carry it. The type itself comes from spring-web, which main does carry, so "
                    + "an injection of the builder compiles and the tests stay green. A measured "
                    + "run of bootJar with that injection prints APPLICATION FAILED TO START and "
                    + "'No qualifying bean of type org.springframework.web.client.RestClient$Builder "
                    + "available', while ApplicationContextTest.contextLoads passes over the same "
                    + "code. Build the client in place instead. RestClient.create() and "
                    + "RestClient.builder() both return a client that needs no injected bean. "
                    + "An ObjectProvider<RestClient.Builder> is worse than "
                    + "the direct injection, not safer: the packaged jar starts, /actuator/health "
                    + "reports UP, and the first getObject() call throws. HttpOgnDeviceDatabase "
                    + "catches RuntimeException and returns an empty device list, so the aircraft "
                    + "sync then reports success and writes nothing.";

    static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            " Residual limit — this rule reads the declared dependency shape, not the runtime "
                    + "classpath. It covers a constructor parameter, a field that carries "
                    + "@Autowired or @Resource, a parameter of a method that carries @Autowired, "
                    + "@Resource or @Bean, and any of these wrapped in a generic type such as "
                    + "ObjectProvider. Each of those shapes has a planted red in "
                    + "PackagedArtifactDependencyShapeGuardRedsOnEveryPlantedViolationTest. "
                    + "jakarta.inject.Inject is absent from this set, because "
                    + "jakarta.inject-api reaches the runtime classpath only and main cannot "
                    + "compile @Inject; add it here together with a planted red when a compile "
                    + "dependency arrives. The rule does not read a lookup that resolves the bean "
                    + "by name or by type from a BeanFactory at run time. It also "
                    + "over-approximates on the constructor side: a class that Spring never "
                    + "instantiates reds although nothing injects it. The rule names one type on "
                    + "purpose. spring-boot-restclient is the only test-only auto-configuration "
                    + "module in this build, and it supplies RestClient.Builder and "
                    + "RestTemplateBuilder. RestTemplateBuilder lives inside that same test-only "
                    + "module, so main cannot compile against it and javac refuses it already. "
                    + "Re-derive this list when you add a testImplementation starter. Re-measure the "
                    + "boot jar and then revisit this rule if you add "
                    + "spring-boot-starter-restclient to the main runtime classpath, because the "
                    + "rule reds on the declaration even after the bean becomes available.";

    @ArchTest
    static final ArchRule no_production_class_injects_a_rest_client_builder =
            noProductionClassInjectsARestClientBuilder();

    static ArchRule noProductionClassInjectsARestClientBuilder() {
        return classes()
                .should(declareNoRestClientBuilderInjectionPoint())
                .as("No production class may inject a RestClient.Builder bean. "
                        + WHY_NO_PRODUCTION_CLASS_MAY_INJECT_THE_REST_CLIENT_BUILDER
                        + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER)
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaClass> declareNoRestClientBuilderInjectionPoint() {
        return new ArchCondition<>("declare no constructor parameter, injected field or injected "
                + "method parameter of type RestClient.Builder") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                for (JavaConstructor constructor : candidate.getConstructors()) {
                    reportParametersCarryingTheBuilder(constructor, "a constructor parameter of",
                            events);
                }
                for (JavaMethod method : candidate.getMethods()) {
                    if (marksAnInjectionPoint(method)) {
                        reportParametersCarryingTheBuilder(method,
                                "an injected parameter of the method", events);
                    }
                }
                for (JavaField field : candidate.getFields()) {
                    if (marksAnInjectionPoint(field) && carriesTheBuilder(field.getType())) {
                        events.add(SimpleConditionEvent.violated(candidate,
                                candidate.getName() + " injects a RestClient.Builder into the "
                                        + "field " + field.getName() + ", declared as "
                                        + field.getType().getName() + ". "
                                        + WHY_NO_PRODUCTION_CLASS_MAY_INJECT_THE_REST_CLIENT_BUILDER));
                    }
                }
            }

            private void reportParametersCarryingTheBuilder(JavaCodeUnit codeUnit,
                                                            String shapeDescription,
                                                            ConditionEvents events) {
                for (JavaType parameterType : codeUnit.getParameterTypes()) {
                    if (carriesTheBuilder(parameterType)) {
                        events.add(SimpleConditionEvent.violated(codeUnit.getOwner(),
                                codeUnit.getOwner().getName()
                                        + " injects a RestClient.Builder through "
                                        + shapeDescription + " " + codeUnit.getName()
                                        + ", declared as " + parameterType.getName() + " at "
                                        + codeUnit.getSourceCodeLocation() + ". "
                                        + WHY_NO_PRODUCTION_CLASS_MAY_INJECT_THE_REST_CLIENT_BUILDER));
                    }
                }
            }
        };
    }

    private static boolean carriesTheBuilder(JavaType declaredType) {
        return declaredType.getAllInvolvedRawTypes().stream()
                .anyMatch(rawType -> REST_CLIENT_BUILDER_FULL_NAME.equals(rawType.getFullName()));
    }

    private static boolean marksAnInjectionPoint(HasAnnotations<?> annotated) {
        return annotated.getAnnotations().stream()
                .anyMatch(annotation -> ANNOTATIONS_THAT_MARK_AN_INJECTION_POINT
                        .contains(annotation.getRawType().getFullName()));
    }
}
