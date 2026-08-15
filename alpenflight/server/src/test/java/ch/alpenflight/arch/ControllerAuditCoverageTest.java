package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.AlpenFlightApplication;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedBy;
import ch.alpenflight.audit.domain.ReadOnlyQuery;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ControllerAuditCoverageTest {

    private static final int MAX_DEPTH = 12;

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackagesOf(AlpenFlightApplication.class);

    @Test
    void every_mutating_controller_method_reaches_audit_trail() {
        List<JavaMethod> uncovered = classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .flatMap(c -> c.getMethods().stream())
                .filter(ControllerAuditCoverageTest::isMutating)
                .filter(this::isUncovered)
                .toList();

        assertThat(uncovered)
                .as("Every mutating controller method must reach AuditTrail.record (transitively)"
                        + " or be annotated @AuditedBy. Missing audit hookup on: %s",
                        uncovered.stream().map(JavaMethod::getFullName).toList())
                .isEmpty();
    }

    private boolean isUncovered(JavaMethod controllerMethod) {
        if (controllerMethod.isAnnotatedWith(AuditedBy.class)
                || controllerMethod.getOwner().isAnnotatedWith(AuditedBy.class)) {
            return false;
        }
        if (controllerMethod.isAnnotatedWith(ReadOnlyQuery.class)) {
            return false;
        }
        return !reachesAuditTrail(controllerMethod);
    }

    private static boolean isMutating(JavaMethod method) {
        return method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class)
                || (method.isAnnotatedWith(RequestMapping.class)
                        && hasMutatingVerb(method));
    }

    private static boolean hasMutatingVerb(JavaMethod method) {
        RequestMapping rm = method.getAnnotationOfType(RequestMapping.class);
        for (org.springframework.web.bind.annotation.RequestMethod m : rm.method()) {
            switch (m) {
                case POST, PUT, PATCH, DELETE -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    private boolean reachesAuditTrail(JavaMethod root) {
        Set<JavaMethod> visited = new HashSet<>();
        Deque<Frame> queue = new ArrayDeque<>();
        queue.push(new Frame(root, 0));
        while (!queue.isEmpty()) {
            Frame frame = queue.pop();
            if (!visited.add(frame.method)) {
                continue;
            }
            if (frame.depth > MAX_DEPTH) {
                continue;
            }
            for (JavaMethodCall call : frame.method.getMethodCallsFromSelf()) {
                String targetType = call.getTargetOwner().getFullName();
                if (AuditTrail.class.getName().equals(targetType)) {
                    return true;
                }
                if (!isOwnProductionCodeSoTheWalkStaysBounded(targetType)) {
                    continue;
                }
                call.getTarget().resolveMember()
                        .ifPresent(member -> queue.push(new Frame(member, frame.depth + 1)));
            }
        }
        return false;
    }

    private boolean isOwnProductionCodeSoTheWalkStaysBounded(String typeName) {
        return typeName.startsWith("ch.alpenflight.");
    }

    private record Frame(JavaMethod method, int depth) {}
}
