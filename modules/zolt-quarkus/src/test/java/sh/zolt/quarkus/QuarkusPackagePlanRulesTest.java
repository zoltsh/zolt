package sh.zolt.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusPackagePlanRulesTest {
    @TempDir
    private Path projectDir;

    private final QuarkusPackagePlanRules rules = new QuarkusPackagePlanRules();

    @Test
    void supportsOnlyQuarkusPackageMode() {
        assertTrue(rules.supports(PackageMode.QUARKUS));
        assertFalse(rules.supports(PackageMode.THIN));
        assertFalse(rules.supports(PackageMode.SPRING_BOOT));
    }

    @Test
    void plansIncludedRuntimeDependencyUnderConfiguredFastJarLibraryDirectory() {
        FrameworkPackagePlanDependency dependency = rules.dependency(
                lockPackage(
                        new PackageId("com.example", "runtime-lib"),
                        "1.2.3",
                        DependencyScope.RUNTIME,
                        Optional.of("com/example/runtime-lib/1.2.3/runtime-lib-1.2.3.jar"),
                        List.of("license:approved")),
                config(".zolt/build"));

        assertEquals("com.example:runtime-lib:1.2.3", dependency.coordinate());
        assertEquals("1.2.3", dependency.version());
        assertEquals(DependencyScope.RUNTIME, dependency.scope());
        assertTrue(dependency.packageDefault());
        assertEquals("included", dependency.disposition());
        assertEquals("quarkus-runtime-lib", dependency.ruleName());
        assertEquals(".zolt/build/quarkus-app/lib/runtime-lib-1.2.3.jar", dependency.location());
        assertEquals("main runtime dependency for Quarkus augmentation output", dependency.reason());
        assertEquals(List.of("license:approved"), dependency.policies());
    }

    @Test
    void fallsBackToCoordinateJarNameWhenLockfileJarPathIsMissing() {
        FrameworkPackagePlanDependency dependency = rules.dependency(
                lockPackage(
                        new PackageId("com.example", "fallback-lib"),
                        "2.0.0",
                        DependencyScope.COMPILE,
                        Optional.empty(),
                        List.of()),
                config(""));

        assertEquals("target/quarkus-app/lib/com.example-fallback-lib-2.0.0.jar", dependency.location());
    }

    @Test
    void omitsNonRuntimeScopesWithScopeSpecificRuleNamesAndFixableReasons() {
        Map<DependencyScope, String> expectedRules = new EnumMap<>(DependencyScope.class);
        expectedRules.put(DependencyScope.PROVIDED, "provided-container-omitted");
        expectedRules.put(DependencyScope.TEST, "test-omitted");
        expectedRules.put(DependencyScope.PROCESSOR, "processor-omitted");
        expectedRules.put(DependencyScope.TEST_PROCESSOR, "processor-omitted");
        expectedRules.put(DependencyScope.QUARKUS_DEPLOYMENT, "quarkus-deployment-omitted");
        expectedRules.put(DependencyScope.TOOL_SPRING_AOT, "spring-aot-tool-omitted");
        expectedRules.put(DependencyScope.TOOL_OPENAPI, "openapi-tool-omitted");
        expectedRules.put(DependencyScope.TOOL_PROTOBUF, "protobuf-tool-omitted");
        expectedRules.put(DependencyScope.TOOL_EXEC, "exec-tool-omitted");
        expectedRules.put(DependencyScope.TOOL_COVERAGE, "coverage-tool-omitted");
        expectedRules.put(DependencyScope.TOOL_GROOVY, "groovy-tool-omitted");

        for (Map.Entry<DependencyScope, String> entry : expectedRules.entrySet()) {
            FrameworkPackagePlanDependency dependency = rules.dependency(
                    lockPackage(
                            new PackageId("com.example", entry.getKey().lockfileName()),
                            "1.0.0",
                            entry.getKey(),
                            Optional.of("modules/" + entry.getKey().lockfileName() + ".jar"),
                            List.of()),
                    config("target"));

            assertEquals("omitted", dependency.disposition());
            assertEquals(entry.getValue(), dependency.ruleName());
            assertEquals("", dependency.location());
            assertTrue(dependency.reason().contains(
                    "scope `" + entry.getKey().lockfileName() + "` does not enter Quarkus runtime packaging"));
        }
    }

    @Test
    void derivesArchiveAndApplicationLayoutFromConfiguredOutputRoot() {
        ProjectConfig config = config(".zolt/build");

        assertEquals(
                projectDir.resolve(".zolt/build/quarkus-app/quarkus-run.jar"),
                rules.archivePath(projectDir, config));
        assertEquals(".zolt/build/quarkus-app/app", rules.applicationLayout(config));
    }

    private static LockPackage lockPackage(
            PackageId packageId,
            String version,
            DependencyScope scope,
            Optional<String> jar,
            List<String> policies) {
        return new LockPackage(
                packageId,
                version,
                "maven-central",
                scope,
                false,
                jar,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                policies);
    }

    private static ProjectConfig config(String outputRoot) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withBuildSettings(new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        outputRoot,
                        outputRoot + "/classes",
                        outputRoot + "/test-classes"));
    }
}
