package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.tenancy.provisioning.application.ClubSpec;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningRequest;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ClubProvisioningEnvelopeDeploymentScopeGuardTest {

    static final Set<String> DEPLOYMENT_MENTIONING_NAMES_THAT_SELECT_NO_TENANT = Set.of(
            "deploymentname",
            "getdeploymentname");

    private static final String DEPLOYMENT_SCOPE_WORD = "deployment";

    private static final int NESTED_CARRIER_DEPTH_BEYOND_WHICH_AN_ENVELOPE_IS_NOT_AN_ENVELOPE = 6;

    private static final Set<String> PACKAGE_PREFIXES_THAT_CARRY_NO_ENVELOPE_OF_OURS = Set.of(
            "java",
            "javax",
            "jakarta",
            "kotlin",
            "com.fasterxml",
            "org.jspecify");

    static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            " Residual limit — this guard reads member names, declared types and annotation values, "
                    + "not intent. It does NOT catch: a deployment identifier smuggled through a "
                    + "component whose name, type and serialized name all avoid the word 'deployment' "
                    + "(a bare UUID called 'homeId'); a deployment identifier packed into an existing "
                    + "String component; a carrier nested deeper than "
                    + NESTED_CARRIER_DEPTH_BEYOND_WHICH_AN_ENVELOPE_IS_NOT_AN_ENVELOPE
                    + " levels; and InternalProvisioningController.ProvisioningRequestBody, which is "
                    + "package-private and @Profile(\"test\") only. The behaviour half of this "
                    + "invariant is MigrationBundleNegativePathIT — it drives a crafted bundle "
                    + "through the real envelope mapper and proves the Club stays out of another "
                    + "owner's Deployment. Extend this guard rather than route around it.";

    static ArchRule clubProvisioningEnvelopeTypesCarryNoDeploymentScopedComponent(
            Set<String> envelopeTypeNames) {
        return classes()
                .that(describe("are club-provisioning envelope types",
                        (JavaClass candidate) -> envelopeTypeNames.contains(candidate.getFullName())))
                .should(carryNoDeploymentScopedComponent())
                .as("A Deployment is chosen by the server from the authenticated caller, never by "
                        + "the uploaded bundle. DeploymentProvisioningService.provision creates the "
                        + "Deployment and hands its id to Club.create, so no type on the path from "
                        + "manifest.json to Club may carry a deployment-scoped component. One added "
                        + "component plus one line in the envelope mapper lets a crafted bundle "
                        + "place a Club inside another owner's Deployment."
                        + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    @ArchTest
    static final ArchRule the_club_provisioning_envelope_carries_no_deployment_scoped_component =
            clubProvisioningEnvelopeTypesCarryNoDeploymentScopedComponent(Set.of(
                    ClubSpec.class.getName(),
                    ProvisioningRequest.class.getName(),
                    BundleManifest.class.getName(),
                    BundleManifest.ClubDeclaration.class.getName()));

    private static ArchCondition<JavaClass> carryNoDeploymentScopedComponent() {
        return new ArchCondition<>("carry no deployment-scoped component") {
            @Override
            public void check(JavaClass envelope, ConditionEvents events) {
                Map<String, String> findings = new LinkedHashMap<>();
                collectDeploymentScopedComponents(
                        envelope, envelope.getSimpleName(), new HashSet<>(), 0, findings);
                findings.values().forEach(finding ->
                        events.add(SimpleConditionEvent.violated(envelope, finding)));
            }
        };
    }

    private static void collectDeploymentScopedComponents(JavaClass carrier,
                                                          String pathSoFar,
                                                          Set<String> alreadyWalked,
                                                          int depth,
                                                          Map<String, String> findings) {
        if (depth > NESTED_CARRIER_DEPTH_BEYOND_WHICH_AN_ENVELOPE_IS_NOT_AN_ENVELOPE
                || !alreadyWalked.add(carrier.getFullName())) {
            return;
        }
        for (JavaField field : carrier.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                continue;
            }
            String path = pathSoFar + "." + field.getName();
            recordNameAndAnnotationFindings(field, field.getName(), path, findings);
            for (JavaClass carried : typesCarriedBy(field.getType())) {
                if (namesTheDeploymentScope(carried.getSimpleName())) {
                    findings.putIfAbsent(path, path + " declares the deployment-scoped type "
                            + carried.getSimpleName());
                } else if (isAnEnvelopeCarrierOfOurs(carried)) {
                    collectDeploymentScopedComponents(
                            carried, path, alreadyWalked, depth + 1, findings);
                }
            }
        }
        for (JavaMethod method : carrier.getMethods()) {
            if (method.getModifiers().contains(JavaModifier.STATIC)
                    || !method.getRawParameterTypes().isEmpty()) {
                continue;
            }
            String path = pathSoFar + "." + method.getName();
            recordNameAndAnnotationFindings(method, method.getName(), path, findings);
            if (namesTheDeploymentScope(method.getRawReturnType().getSimpleName())) {
                findings.putIfAbsent(path, path + " returns the deployment-scoped type "
                        + method.getRawReturnType().getSimpleName());
            }
        }
    }

    private static void recordNameAndAnnotationFindings(JavaMember member,
                                                        String memberName,
                                                        String path,
                                                        Map<String, String> findings) {
        if (namesTheDeploymentScope(memberName)) {
            findings.putIfAbsent(path, path + " names the deployment scope");
            return;
        }
        serializedNamesOf(member)
                .filter(ClubProvisioningEnvelopeDeploymentScopeGuardTest::namesTheDeploymentScope)
                .findFirst()
                .ifPresent(serializedName -> findings.putIfAbsent(path,
                        path + " binds the deployment scope under the serialized name \""
                                + serializedName + "\""));
    }

    private static boolean namesTheDeploymentScope(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace("_", "");
        return lower.contains(DEPLOYMENT_SCOPE_WORD)
                && !DEPLOYMENT_MENTIONING_NAMES_THAT_SELECT_NO_TENANT.contains(lower);
    }

    private static Stream<String> serializedNamesOf(JavaMember member) {
        return member.getAnnotations().stream()
                .flatMap(ClubProvisioningEnvelopeDeploymentScopeGuardTest::stringValuesOf);
    }

    private static Stream<String> stringValuesOf(JavaAnnotation<?> annotation) {
        return annotation.getProperties().values().stream()
                .flatMap(value -> value instanceof Object[] array
                        ? Arrays.stream(array)
                        : Stream.of(value))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    private static List<JavaClass> typesCarriedBy(JavaType declaredType) {
        if (declaredType instanceof JavaParameterizedType parameterized) {
            List<JavaClass> carried = new ArrayList<>();
            carried.add(parameterized.toErasure());
            for (JavaType typeArgument : parameterized.getActualTypeArguments()) {
                carried.addAll(typesCarriedBy(typeArgument));
            }
            return carried;
        }
        return List.of(declaredType.toErasure());
    }

    private static boolean isAnEnvelopeCarrierOfOurs(JavaClass carried) {
        if (carried.isPrimitive() || carried.isEnum() || carried.isArray()
                || carried.isInterface()) {
            return false;
        }
        String packageName = carried.getPackageName();
        return PACKAGE_PREFIXES_THAT_CARRY_NO_ENVELOPE_OF_OURS.stream()
                .noneMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
    }
}
