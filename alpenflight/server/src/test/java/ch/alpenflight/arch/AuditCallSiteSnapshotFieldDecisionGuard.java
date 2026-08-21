package ch.alpenflight.arch;

import ch.alpenflight.arch.AuditRedactionCoverageTest.RedactionPolicy;
import ch.alpenflight.audit.domain.AuditRedact;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

final class AuditCallSiteSnapshotFieldDecisionGuard {

    static final String AUDITED_TARGET = "ch.alpenflight.audit.domain.AuditedTarget";
    static final Set<String> SNAPSHOT_FACTORIES = Set.of("created", "updated", "deleted");

    static final String UNRESOLVABLE_ENTITY_TYPE = "<entity-type-not-a-compile-time-constant>";
    static final String UNRESOLVABLE_SNAPSHOT_TYPE = "<snapshot-type-not-a-class>";
    static final String UNLOADABLE_SNAPSHOT_TYPE = "<snapshot-type-not-loadable>";

    static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            "Residual limit: this guard reads the STATIC type of the snapshot argument and the "
                    + "top-level fields of that type. It does not follow the redactor into nested "
                    + "objects, and a subclass passed through a supertype-typed variable is judged "
                    + "by the supertype.";

    private AuditCallSiteSnapshotFieldDecisionGuard() {}

    record UndecidedSnapshotFields(String callSiteOwner,
                                   String entityType,
                                   String snapshotType,
                                   List<String> undecidedFields) implements Comparable<UndecidedSnapshotFields> {

        static final String COLUMN_SEPARATOR = " | ";

        String render() {
            return callSiteOwner + COLUMN_SEPARATOR + entityType + COLUMN_SEPARATOR + snapshotType
                    + COLUMN_SEPARATOR + String.join(",", undecidedFields);
        }

        static UndecidedSnapshotFields parse(String registerLine) {
            String[] columns = registerLine.split(" \\| ", -1);
            if (columns.length != 4) {
                throw new AssertionError(
                        "A register line needs 4 columns separated by \" | \": " + registerLine);
            }
            List<String> fields = columns[3].isBlank()
                    ? List.of()
                    : List.of(columns[3].split(","));
            return new UndecidedSnapshotFields(columns[0], columns[1], columns[2], fields);
        }

        @Override
        public int compareTo(UndecidedSnapshotFields other) {
            return render().compareTo(other.render());
        }
    }

    static List<UndecidedSnapshotFields> scan(List<Path> sourceFiles,
                                              String classpath,
                                              RedactionPolicy policy,
                                              ClassLoader snapshotTypeLoader) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("No system Java compiler — this guard cannot resolve call-site "
                    + "snapshot types, so it refuses to report a green it did not prove.");
        }
        List<File> files = sourceFiles.stream().map(Path::toFile).toList();
        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        Set<UndecidedSnapshotFields> found = new TreeSet<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager,
                    diagnostic -> {
                        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                            errors.add(diagnostic);
                        }
                    },
                    List.of("-proc:none", "-nowarn", "-classpath", classpath),
                    null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            task.analyze();
            if (!errors.isEmpty()) {
                throw new AssertionError("The guard could not type-check the audit call sites, so it "
                        + "cannot prove anything about them: " + errors);
            }
            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            for (CompilationUnitTree unit : parsed) {
                new SnapshotArgumentScanner(trees, elements, policy, snapshotTypeLoader, found)
                        .scan(unit, null);
            }
        } catch (IOException e) {
            throw new AssertionError("The guard cannot read the audit call-site sources", e);
        }
        return List.copyOf(found);
    }

    private static final class SnapshotArgumentScanner extends TreePathScanner<Void, Void> {

        private static final int FIRST_SNAPSHOT_ARGUMENT_INDEX = 2;

        private final Trees trees;
        private final Elements elements;
        private final RedactionPolicy policy;
        private final ClassLoader snapshotTypeLoader;
        private final Set<UndecidedSnapshotFields> found;

        private SnapshotArgumentScanner(Trees trees,
                                        Elements elements,
                                        RedactionPolicy policy,
                                        ClassLoader snapshotTypeLoader,
                                        Set<UndecidedSnapshotFields> found) {
            this.trees = trees;
            this.elements = elements;
            this.policy = policy;
            this.snapshotTypeLoader = snapshotTypeLoader;
            this.found = found;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
            if (isAuditedTargetSnapshotFactory()) {
                record(invocation);
            }
            return super.visitMethodInvocation(invocation, null);
        }

        private boolean isAuditedTargetSnapshotFactory() {
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof ExecutableElement method)) {
                return false;
            }
            Element owner = method.getEnclosingElement();
            return owner instanceof TypeElement type
                    && elements.getBinaryName(type).contentEquals(AUDITED_TARGET)
                    && SNAPSHOT_FACTORIES.contains(method.getSimpleName().toString());
        }

        private void record(MethodInvocationTree invocation) {
            List<? extends ExpressionTree> arguments = invocation.getArguments();
            String entityType = entityTypeOf(arguments.get(0));
            String callSiteOwner = callSiteOwnerOf();
            for (int i = FIRST_SNAPSHOT_ARGUMENT_INDEX; i < arguments.size(); i++) {
                TypeMirror snapshot = trees.getTypeMirror(
                        new TreePath(getCurrentPath(), arguments.get(i)));
                if (snapshot == null || snapshot.getKind() == TypeKind.NULL) {
                    continue;
                }
                if (entityType == null) {
                    found.add(new UndecidedSnapshotFields(callSiteOwner,
                            UNRESOLVABLE_ENTITY_TYPE, String.valueOf(snapshot), List.of()));
                    continue;
                }
                if (!(snapshot instanceof DeclaredType declared)
                        || !(declared.asElement() instanceof TypeElement snapshotType)) {
                    found.add(new UndecidedSnapshotFields(callSiteOwner, entityType,
                            String.valueOf(snapshot), List.of(UNRESOLVABLE_SNAPSHOT_TYPE)));
                    continue;
                }
                String binaryName = elements.getBinaryName(snapshotType).toString();
                List<String> undecided = undecidedFieldsOf(entityType, binaryName);
                if (!undecided.isEmpty()) {
                    found.add(new UndecidedSnapshotFields(
                            callSiteOwner, entityType, binaryName, undecided));
                }
            }
        }

        private List<String> undecidedFieldsOf(String entityType, String snapshotBinaryName) {
            if (policy.denyAll().contains(entityType)) {
                return List.of();
            }
            Set<String> allow = policy.allowed(entityType);
            Class<?> snapshotType;
            try {
                snapshotType = Class.forName(snapshotBinaryName, false, snapshotTypeLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                return List.of(UNLOADABLE_SNAPSHOT_TYPE);
            }
            List<String> undecided = new ArrayList<>();
            for (Class<?> level = snapshotType;
                 level != null && level != Object.class;
                 level = level.getSuperclass()) {
                for (Field field : level.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    if (field.isAnnotationPresent(AuditRedact.class)) {
                        continue;
                    }
                    if (!allow.contains(field.getName())) {
                        undecided.add(field.getName());
                    }
                }
            }
            undecided.sort(String::compareTo);
            return List.copyOf(undecided);
        }

        private String callSiteOwnerOf() {
            CompilationUnitTree unit = getCurrentPath().getCompilationUnit();
            String name = unit.getSourceFile().getName().replace(File.separatorChar, '/');
            int packageRoot = name.lastIndexOf("/ch/alpenflight/");
            String relative = packageRoot < 0 ? name : name.substring(packageRoot + 1);
            return relative.replace(".java", "").replace('/', '.');
        }

        private @Nullable String entityTypeOf(ExpressionTree argument) {
            if (argument instanceof LiteralTree literal && literal.getValue() instanceof String text) {
                return text;
            }
            Element element = trees.getElement(new TreePath(getCurrentPath(), argument));
            if (element instanceof VariableElement variable) {
                if (variable.getConstantValue() instanceof String text) {
                    return text;
                }
                String fromInitializer = simpleNameOfClassLiteralInitializerOf(variable);
                if (fromInitializer != null) {
                    return fromInitializer;
                }
            }
            return simpleNameOfClassLiteralAccessor(argument, getCurrentPath());
        }

        private @Nullable String simpleNameOfClassLiteralInitializerOf(
                VariableElement constant) {
            TreePath declarationPath = trees.getPath(constant);
            if (declarationPath == null
                    || !(declarationPath.getLeaf()
                            instanceof VariableTree declaration)) {
                return null;
            }
            ExpressionTree initializer = declaration.getInitializer();
            return initializer == null
                    ? null
                    : simpleNameOfClassLiteralAccessor(initializer, declarationPath);
        }

        private @Nullable String simpleNameOfClassLiteralAccessor(
                ExpressionTree expression, TreePath contextPath) {
            if (!(expression instanceof MethodInvocationTree invocation)
                    || !invocation.getArguments().isEmpty()
                    || !(invocation.getMethodSelect() instanceof MemberSelectTree accessor)
                    || !accessor.getIdentifier().contentEquals("getSimpleName")
                    || !(accessor.getExpression() instanceof MemberSelectTree classLiteral)
                    || !classLiteral.getIdentifier().contentEquals("class")) {
                return null;
            }
            TypeMirror literalType = trees.getTypeMirror(
                    new TreePath(contextPath, classLiteral.getExpression()));
            if (literalType instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement type) {
                return type.getSimpleName().toString();
            }
            return null;
        }
    }

    static List<Path> javaSourcesUnder(Path sourceRoot) {
        try (var walk = Files.walk(sourceRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError("Cannot walk " + sourceRoot.toAbsolutePath(), e);
        }
    }

    static String renderRefusal(List<UndecidedSnapshotFields> unregistered,
                                List<UndecidedSnapshotFields> staleRegisterLines) {
        StringBuilder message = new StringBuilder();
        if (!unregistered.isEmpty()) {
            message.append("These audit call sites pass a snapshot whose fields the recorded "
                    + "entityType does not decide, so the persisted row renders them "
                    + "\"[redacted]\" and the audit trail is silently useless:\n");
            for (UndecidedSnapshotFields violation : unregistered) {
                message.append("  - ").append(violation.render()).append('\n');
            }
            message.append("Fix the call site (record the entityType that names the snapshot), or "
                    + "decide the fields (allow-list them under audit.redaction.entities, or "
                    + "annotate them @AuditRedact).\n");
        }
        if (!staleRegisterLines.isEmpty()) {
            message.append("These register lines no longer describe a real call site. The register "
                    + "only shrinks — delete them:\n");
            for (UndecidedSnapshotFields stale : staleRegisterLines) {
                message.append("  - ").append(stale.render()).append('\n');
            }
        }
        message.append(RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
        return message.toString();
    }
}
