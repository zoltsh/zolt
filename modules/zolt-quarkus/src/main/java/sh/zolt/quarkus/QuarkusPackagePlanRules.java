package sh.zolt.quarkus;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;

public final class QuarkusPackagePlanRules implements FrameworkPackagePlanRules {
    @Override
    public String evidenceIdentity() {
        return "quarkus-fast-jar-v1";
    }

    @Override
    public boolean supports(PackageMode mode) {
        return mode == PackageMode.QUARKUS;
    }

    @Override
    public FrameworkPackagePlanDependency dependency(LockPackage lockPackage, ProjectConfig config) {
        boolean included = lockPackage.scope().entersMainRuntimeClasspath();
        return new FrameworkPackagePlanDependency(
                coordinate(lockPackage),
                lockPackage.version(),
                lockPackage.scope(),
                included ? "included" : "omitted",
                included ? "quarkus-runtime-lib" : omissionRule(lockPackage.scope()),
                included ? outputRoot(config) + "/quarkus-app/lib/" + nestedJarName(lockPackage) : "",
                included
                        ? "main runtime dependency for Quarkus augmentation output"
                        : "scope `" + lockPackage.scope().lockfileName() + "` does not enter Quarkus runtime packaging",
                lockPackage.policies());
    }

    @Override
    public Path archivePath(Path projectRoot, ProjectConfig config) {
        return ProjectPaths.output(projectRoot, "package archive", outputRoot(config) + "/quarkus-app/quarkus-run.jar");
    }

    @Override
    public String applicationLayout(ProjectConfig config) {
        return outputRoot(config) + "/quarkus-app/app";
    }

    private static String outputRoot(ProjectConfig config) {
        return config == null || config.build().outputRoot().isBlank() ? "target" : config.build().outputRoot();
    }

    private static String omissionRule(DependencyScope scope) {
        return switch (scope) {
            case PROVIDED -> "provided-container-omitted";
            case DEV -> "dev-only-omitted";
            case TEST -> "test-omitted";
            case PROCESSOR, TEST_PROCESSOR -> "processor-omitted";
            case QUARKUS_DEPLOYMENT -> "quarkus-deployment-omitted";
            case TOOL_SPRING_AOT -> "spring-aot-tool-omitted";
            case TOOL_OPENAPI -> "openapi-tool-omitted";
            case TOOL_PROTOBUF -> "protobuf-tool-omitted";
            case TOOL_EXEC -> "exec-tool-omitted";
            case TOOL_COVERAGE -> "coverage-tool-omitted";
            case COMPILE, RUNTIME -> "non-runtime-omitted";
        };
    }

    private static String nestedJarName(LockPackage lockPackage) {
        return lockPackage.jar()
                .map(Path::of)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> lockPackage.packageId().toString().replace(':', '-') + "-" + lockPackage.version() + ".jar");
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }
}
