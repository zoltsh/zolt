package sh.zolt.build.packageplan;

import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PackagePlanService {
    private final ZoltLockfileReader lockfileReader;
    private final List<FrameworkPackagePlanRules> packagePlanRules;

    public PackagePlanService() {
        this(List.of());
    }

    public PackagePlanService(List<FrameworkPackagePlanRules> packagePlanRules) {
        this(new ZoltLockfileReader(), packagePlanRules);
    }

    PackagePlanService(ZoltLockfileReader lockfileReader) {
        this(lockfileReader, List.of());
    }

    PackagePlanService(ZoltLockfileReader lockfileReader, List<FrameworkPackagePlanRules> packagePlanRules) {
        this.lockfileReader = lockfileReader;
        this.packagePlanRules = packagePlanRules == null ? List.of() : List.copyOf(packagePlanRules);
    }

    public PackagePlan plan(Path projectDirectory, ProjectConfig config) {
        Path projectRoot = projectRoot(projectDirectory);
        return plan(projectRoot, config, projectRoot.resolve("zolt.lock"));
    }

    public PackagePlan plan(Path projectDirectory, ProjectConfig config, Path lockfilePath) {
        Path projectRoot = projectRoot(projectDirectory);
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath.toAbsolutePath().normalize());
        return plan(projectRoot, config, lockfile);
    }

    public PackagePlan plan(
            Path projectDirectory,
            ProjectConfig config,
            ZoltLockfile lockfile) {
        Path projectRoot = projectRoot(projectDirectory);
        PackageMode mode = config.packageSettings().mode();
        Set<PackageId> providedPackageIds = providedPackageIds(lockfile);
        Optional<FrameworkPackagePlanRules> modeRules = packagePlanRules(mode);
        List<PackagePlanDependency> dependencies = mode == PackageMode.BOM
                ? List.of()
                : lockfile.packages().stream()
                        .filter(PackagePlanService::packageInput)
                        .sorted(Comparator.comparing(PackagePlanService::sortKey))
                        .map(lockPackage -> PackagePlanDependencyClassifier.dependency(
                                mode,
                                lockPackage,
                                providedPackageIds,
                                modeRules,
                                config))
                        .toList();
        return new PackagePlan(
                projectRoot,
                mode,
                archivePath(projectRoot, config, mode, modeRules),
                applicationOutput(projectRoot, config, mode),
                applicationLayout(mode, modeRules, config),
                runtimeClasspathPath(projectRoot, config, mode),
                dependencies,
                warnings(mode, modeRules, dependencies));
    }

    private static Path applicationOutput(
            Path projectRoot,
            ProjectConfig config,
            PackageMode mode) {
        if (mode == PackageMode.BOM) {
            return ProjectPaths.output(
                    projectRoot,
                    "BOM package output",
                    config.build().outputRoot() + "/publish");
        }
        return ProjectPaths.output(
                projectRoot,
                "[build].output",
                config.build().output());
    }

    private static boolean packageInput(LockPackage lockPackage) {
        return lockPackage.jar().isPresent()
                || (lockPackage.workspace().isPresent()
                        && lockPackage.workspaceOutput().isPresent());
    }

    private Optional<FrameworkPackagePlanRules> packagePlanRules(PackageMode mode) {
        return packagePlanRules.stream()
                .filter(rules -> rules.supports(mode))
                .findFirst();
    }

    private static Set<PackageId> providedPackageIds(ZoltLockfile lockfile) {
        Set<PackageId> packageIds = new LinkedHashSet<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.scope() == DependencyScope.PROVIDED && lockPackage.direct()) {
                packageIds.add(lockPackage.packageId());
            }
        }
        return Set.copyOf(packageIds);
    }

    private static List<PackagePlanWarning> warnings(
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
            if (!("included".equals(dependency.disposition()))
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
                            + "`"
                            + ".",
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

    private static Path archivePath(
            Path projectRoot,
            ProjectConfig config,
            PackageMode mode,
            Optional<FrameworkPackagePlanRules> packagePlanRules) {
        if (packagePlanRules.isPresent()) {
            return packagePlanRules.orElseThrow().archivePath(projectRoot, config);
        }
        return switch (mode) {
            case WAR, SPRING_BOOT_WAR -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".war");
            case QUARKUS -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".jar");
            case BOM -> ProjectPaths.output(
                    projectRoot,
                    "BOM package artifact",
                    config.build().outputRoot() + "/publish/" + artifactBaseName(config) + ".pom");
            default -> ProjectPaths.output(
                    projectRoot,
                    "package archive",
                    config.build().outputRoot() + "/" + artifactBaseName(config) + ".jar");
        };
    }

    private static Optional<Path> runtimeClasspathPath(Path projectRoot, ProjectConfig config, PackageMode mode) {
        if (mode != PackageMode.THIN) {
            return Optional.empty();
        }
        return Optional.of(ProjectPaths.output(
                projectRoot,
                "package runtime classpath",
                config.build().outputRoot() + "/" + artifactBaseName(config) + ".runtime-classpath"));
    }

    private static String artifactBaseName(ProjectConfig config) {
        return ProjectPaths.filenameComponent("[project].name", config.project().name())
                + "-"
                + ProjectPaths.filenameComponent("[project].version", config.project().version());
    }

    private static String applicationLayout(
            PackageMode mode,
            Optional<FrameworkPackagePlanRules> packagePlanRules,
            ProjectConfig config) {
        if (packagePlanRules.isPresent()) {
            return packagePlanRules.orElseThrow().applicationLayout(config);
        }
        return switch (mode) {
            case THIN, UBER -> "archive root";
            case SPRING_BOOT -> "BOOT-INF/classes";
            case WAR, SPRING_BOOT_WAR -> "WEB-INF/classes";
            case QUARKUS -> "framework package output";
            case BOM -> "dependencyManagement POM";
        };
    }

    private static String sortKey(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + lockPackage.scope().lockfileName();
    }

    private static Path projectRoot(Path projectDirectory) {
        return projectDirectory.toAbsolutePath().normalize();
    }
}
