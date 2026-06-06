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

/**
 * S-027 coverage guard. Every {@code @RestController} method that handles
 * a mutating HTTP verb (POST/PUT/PATCH/DELETE) under {@code /api/v1/**}
 * must either:
 *
 * <ul>
 *   <li>transitively call {@link AuditTrail#record} or
 *       {@link AuditTrail#recordFailed}; OR</li>
 *   <li>carry {@link AuditedBy} on the method or the enclosing
 *       controller class — the escape for hand-off paths (async / lambda)
 *       whose call graph ArchUnit can't trace.</li>
 * </ul>
 *
 * <p>Caught at CI build time — a new mutating endpoint that forgets the
 * audit hookup fails this test instead of silently shipping audit gaps.
 *
 * <p>The walk is a bounded DFS over the production {@link JavaClass}
 * graph; stops at the first {@code AuditTrail.record*} hit or after
 * exhausting the method's reachable call set.
 */
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
        // Read-shaped POST (paged-list / search / lookup carrying its query in
        // the body) legitimately emits no audit event — the @ReadOnlyQuery
        // marker is the narrow exemption (NOT a way to silence a real mutation).
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
                default -> { /* GET / HEAD / OPTIONS / TRACE — not mutating. */ }
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
                if (!isReachableProductionMethod(targetType)) {
                    continue;
                }
                call.getTarget().resolveMember()
                        .ifPresent(member -> queue.push(new Frame(member, frame.depth + 1)));
            }
        }
        return false;
    }

    private boolean isReachableProductionMethod(String typeName) {
        // Bound the walk to project code — chasing into Spring / Hibernate
        // would blow up the search without changing the verdict.
        return typeName.startsWith("ch.alpenflight.");
    }

    private record Frame(JavaMethod method, int depth) {}
}
