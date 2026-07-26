package sh.zolt.build.packageplan;

import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.project.PackageMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PackagePlanWarnings {
    private PackagePlanWarnings() {
    }

    static List<PackagePlanWarning> forPlan(
            PackageMode mode,
            Optional<FrameworkPackagePlanRules> modeRules,
            List<PackagePlanDependency> dependencies) {
        List<PackagePlanWarning> warnings = new ArrayList<>();
        if (mode == PackageMode.QUARKUS && modeRules.isEmpty()) {
            warnings.add(new PackagePlanWarning(
                    "FRAMEWORK_PACKAGE_PLAN_RULES_MISSING",
                    "[package].mode",
                    "framework-package-plan-rules-missing",
                    "Package mode `quarkus` requires framework-aware package plan rules, but none are installed.",
                    "Run package quality through the Zolt application composition that installs Quarkus package plan rules."));
        }
        if (mode != PackageMode.WAR && mode != PackageMode.SPRING_BOOT_WAR) {
            return List.copyOf(warnings);
        }
        for (PackagePlanDependency dependency : dependencies) {
            if (!"included".equals(dependency.disposition())
                    || !isContainerDependency(dependency.coordinate())) {
                continue;
            }
            warnings.add(new PackagePlanWarning(
                    "CONTAINER_DEPENDENCY_PACKAGED",
                    dependency.coordinate(),
                    dependency.ruleName(),
                    "Container-style dependency `" + dependency.coordinate() + "` is packaged in "
                            + dependency.location()
                            + " by package rule `"
                            + dependency.ruleName()
                            + "`.",
                    "Move it to [provided.dependencies] when the servlet container supplies it, then run `zolt resolve`."));
        }
        return List.copyOf(warnings);
    }

    private static boolean isContainerDependency(String coordinate) {
        return coordinate.startsWith("jakarta.servlet:")
                || coordinate.startsWith("javax.servlet:")
                || coordinate.startsWith("org.apache.tomcat:")
                || coordinate.startsWith("org.apache.tomcat.embed:")
                || coordinate.contains(":tomcat-embed-");
    }
}
