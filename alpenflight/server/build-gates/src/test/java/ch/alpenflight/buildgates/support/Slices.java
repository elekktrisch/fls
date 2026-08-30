package ch.alpenflight.buildgates.support;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Slices {

    public static final String[] PRODUCTION_MODULE_ROOTS = {
        "ch.alpenflight.core", "ch.alpenflight.modulesopen", "ch.alpenflight.modulespro"
    };

    private Slices() {
    }

    public static SortedMap<String, SortedSet<String>> childPackagesByModuleRoot(
            JavaClasses classes, String moduleRootPackage) {
        SortedMap<String, SortedSet<String>> slices = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            String packageName = javaClass.getPackageName();
            boolean underModuleRoot =
                    packageName.equals(moduleRootPackage) || packageName.startsWith(moduleRootPackage + ".");
            if (!underModuleRoot) {
                continue;
            }
            String remainder = packageName.equals(moduleRootPackage)
                    ? ""
                    : packageName.substring(moduleRootPackage.length() + 1);
            if (remainder.isEmpty()) {
                continue;
            }
            String[] segments = remainder.split("\\.");
            String sliceName = moduleRootPackage + "." + segments[0];
            SortedSet<String> childPackages = slices.computeIfAbsent(sliceName, key -> new TreeSet<>());
            if (segments.length >= 2) {
                childPackages.add(segments[1]);
            }
        }
        return slices;
    }

    public static Set<Map.Entry<String, SortedSet<String>>> childPackagesAcrossModuleRoots(
            JavaClasses classes, String... moduleRootPackages) {
        SortedMap<String, SortedSet<String>> allSlices = new TreeMap<>();
        for (String moduleRootPackage : moduleRootPackages) {
            allSlices.putAll(childPackagesByModuleRoot(classes, moduleRootPackage));
        }
        return allSlices.entrySet();
    }
}
