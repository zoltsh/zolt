package sh.zolt.build.packageplan;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.build.packageauthority.ProvidedPackagingOverrides;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.util.Optional;

final class PackagePlanDependencyClassifier {
    private PackagePlanDependencyClassifier() {}

    static PackagePlanDependency dependency(
            PackageMode mode,
            LockPackage lockPackage,
            ProvidedPackagingOverrides providedOverrides,
            Optional<FrameworkPackagePlanRules> packagePlanRules,
            ProjectConfig config) {
        NestedArtifactIdentity identity = NestedArtifactIdentity.of(lockPackage);
        String nestedJar = identity.nestedJarName();
        return switch (mode) {
            case THIN -> thinDependency(lockPackage);
            case SPRING_BOOT -> springBootDependency(lockPackage, nestedJar);
            case WAR -> warDependency(
                    lockPackage,
                    identity,
                    nestedJar,
                    providedOverrides);
            case SPRING_BOOT_WAR -> springBootWarDependency(
                    lockPackage,
                    identity,
                    nestedJar,
                    providedOverrides);
            case QUARKUS -> packagePlanRules
                    .map(rules -> dependency(rules.dependency(lockPackage, config)))
                    .orElseGet(() -> unsupportedFrameworkDependency(mode, lockPackage));
            case UBER -> uberDependency(lockPackage);
            case BOM -> throw new IllegalStateException("BOM projects publish a POM and package no dependencies.");
        };
    }

    private static PackagePlanDependency dependency(FrameworkPackagePlanDependency dependency) {
        return new PackagePlanDependency(
                dependency.coordinate(),
                dependency.version(),
                dependency.scope(),
                dependency.lanes(),
                dependency.packageDefault(),
                dependency.laneDisposition(),
                dependency.disposition(),
                dependency.ruleName(),
                dependency.location(),
                dependency.reason(),
                dependency.policies());
    }

    private static PackagePlanDependency unsupportedFrameworkDependency(PackageMode mode, LockPackage lockPackage) {
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                "unsupported",
                "framework-package-plan-rules-missing",
                "",
                "package mode `" + mode.configValue() + "` requires framework package plan rules",
                lockPackage.policies());
    }

    private static PackagePlanDependency thinDependency(LockPackage lockPackage) {
        boolean included = lockPackage.scope().packagedByDefault();
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "runtime-classpath" : "omitted",
                included ? "thin-runtime-classpath" : PackagePlanDependencyOmissions.rule(lockPackage.scope(), false),
                included ? "runtime-classpath sidecar" : "",
                included
                        ? "dependency remains outside the thin jar and is written to the runtime classpath sidecar"
                        : PackagePlanDependencyOmissions.reason(lockPackage.scope(), false),
                lockPackage.policies());
    }

    private static PackagePlanDependency uberDependency(LockPackage lockPackage) {
        boolean included = lockPackage.scope().packagedByDefault();
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "included" : "omitted",
                included ? "uber-runtime-merged" : PackagePlanDependencyOmissions.rule(lockPackage.scope(), false),
                included ? "archive root" : "",
                included
                        ? "runtime dependency classes and resources are merged into the uber jar"
                        : PackagePlanDependencyOmissions.reason(lockPackage.scope(), false),
                lockPackage.policies());
    }

    private static PackagePlanDependency springBootDependency(LockPackage lockPackage, String nestedJar) {
        if (isExpandedSpringBootLoader(lockPackage)) {
            return new PackagePlanDependency(
                    coordinate(lockPackage),
                    lockPackage.version(),
                    lockPackage.scope(),
                    "loader",
                    "spring-boot-loader-expanded",
                    "archive root",
                    "Spring Boot loader classes are expanded at the archive root",
                    lockPackage.policies());
        }
        boolean included = lockPackage.scope().packagedByDefault();
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "included" : "omitted",
                included ? "spring-boot-runtime-nested" : PackagePlanDependencyOmissions.rule(lockPackage.scope(), false),
                included ? "BOOT-INF/lib/" + nestedJar : "",
                included
                        ? "runtime dependency packaged as a nested Spring Boot jar"
                        : PackagePlanDependencyOmissions.reason(lockPackage.scope(), false),
                lockPackage.policies());
    }

    private static PackagePlanDependency warDependency(
            LockPackage lockPackage,
            NestedArtifactIdentity identity,
            String nestedJar,
            ProvidedPackagingOverrides providedOverrides) {
        if (isProvidedCoordinateOverride(
                PackageMode.WAR,
                lockPackage,
                identity,
                providedOverrides)) {
            return providedCoordinateOverride(lockPackage, false);
        }
        boolean included = lockPackage.scope().packagedByDefault();
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "included" : "omitted",
                included ? "war-runtime-lib" : PackagePlanDependencyOmissions.rule(lockPackage.scope(), false),
                included ? "WEB-INF/lib/" + nestedJar : "",
                included
                        ? "runtime dependency packaged for the servlet container"
                        : PackagePlanDependencyOmissions.reason(lockPackage.scope(), false),
                lockPackage.policies());
    }

    private static PackagePlanDependency springBootWarDependency(
            LockPackage lockPackage,
            NestedArtifactIdentity identity,
            String nestedJar,
            ProvidedPackagingOverrides providedOverrides) {
        if (isExpandedSpringBootLoader(lockPackage)) {
            return new PackagePlanDependency(
                    coordinate(lockPackage),
                    lockPackage.version(),
                    lockPackage.scope(),
                    "loader",
                    "spring-boot-war-loader-expanded",
                    "archive root",
                    "Spring Boot WAR launcher classes are expanded at the archive root",
                    lockPackage.policies());
        }
        if (lockPackage.scope() == DependencyScope.PROVIDED) {
            return new PackagePlanDependency(
                    coordinate(lockPackage),
                    lockPackage.version(),
                    lockPackage.scope(),
                    "provided",
                    "spring-boot-war-provided-lib",
                    "WEB-INF/lib-provided/" + nestedJar,
                    "provided dependency is available to java -jar without entering servlet container WEB-INF/lib",
                    lockPackage.policies());
        }
        if (isProvidedCoordinateOverride(
                PackageMode.SPRING_BOOT_WAR,
                lockPackage,
                identity,
                providedOverrides)) {
            return providedCoordinateOverride(lockPackage, true);
        }
        boolean included = lockPackage.scope().packagedByDefault();
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "included" : "omitted",
                included ? "spring-boot-war-runtime-lib" : PackagePlanDependencyOmissions.rule(lockPackage.scope(), true),
                included ? "WEB-INF/lib/" + nestedJar : "",
                included
                        ? "runtime dependency packaged for the Spring Boot WAR launcher"
                        : PackagePlanDependencyOmissions.reason(lockPackage.scope(), false),
                lockPackage.policies());
    }

    private static boolean isProvidedCoordinateOverride(
            PackageMode mode,
            LockPackage lockPackage,
            NestedArtifactIdentity identity,
            ProvidedPackagingOverrides providedOverrides) {
        return lockPackage.scope() != DependencyScope.PROVIDED
                && providedOverrides.suppresses(identity, mode);
    }

    private static boolean isExpandedSpringBootLoader(
            LockPackage lockPackage) {
        return SpringBootLoaderArtifact.isDefaultLoader(lockPackage)
                && lockPackage.scope().entersMainRuntimeClasspath();
    }

    private static PackagePlanDependency providedCoordinateOverride(
            LockPackage lockPackage,
            boolean springBootWar) {
        return new PackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                "omitted",
                springBootWar ? "spring-boot-war-provided-coordinate-override" : "war-provided-coordinate-override",
                "",
                "the exact artifact variant is directly declared in [provided.dependencies], so this runtime path is omitted from the deployable runtime lib directory",
                lockPackage.policies());
    }

    private static String coordinate(LockPackage lockPackage) {
        return NestedArtifactIdentity.of(lockPackage).coordinate();
    }
}
