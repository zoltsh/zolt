package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackagePlanNestedDependencies {
    private PackagePlanNestedDependencies() {
    }

    static List<PackagePlanDependency> canonicalize(
            List<PackagePlanDependency> dependencies) {
        List<PackagePlanDependency> canonical = new ArrayList<>();
        Map<String, PackagePlanDependency> byLocation = new LinkedHashMap<>();
        for (PackagePlanDependency dependency : dependencies) {
            if (!nestedLocation(dependency.location())) {
                canonical.add(dependency);
                continue;
            }
            PackagePlanDependency previous =
                    byLocation.putIfAbsent(
                            dependency.location(),
                            dependency);
            if (previous == null) {
                canonical.add(dependency);
                continue;
            }
            if (!previous.coordinate().equals(dependency.coordinate())) {
                throw new PackageException(
                        "Package plan selects duplicate nested path "
                                + dependency.location()
                                + " for "
                                + previous.coordinate()
                                + " and "
                                + dependency.coordinate()
                                + ".");
            }
        }
        return List.copyOf(canonical);
    }

    private static boolean nestedLocation(String location) {
        return location.startsWith("BOOT-INF/lib/")
                || location.startsWith("WEB-INF/lib/")
                || location.startsWith("WEB-INF/lib-provided/");
    }
}
