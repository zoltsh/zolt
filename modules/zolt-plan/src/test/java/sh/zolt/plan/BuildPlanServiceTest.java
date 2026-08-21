package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import sh.zolt.project.TestRuntimeSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPlanServiceTest {
    private final BuildPlanService service = new BuildPlanService();

    @TempDir
    private Path projectDir;

    @Test
    void blocksMissingLockfileWithResolveNextStep() {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.BUILD, Optional.empty());

        PlanNode lockfile = node(plan, "lockfile");
        assertTrue(plan.blocked());
        assertEquals(PlanNodeStatus.BLOCKED, lockfile.status());
        PlanBlocker blocker = blocker(lockfile, "missing-lockfile");
        assertEquals("zolt.lock is missing; plan will not resolve or download artifacts.", blocker.message());
        assertEquals("Run `zolt resolve` first, then rerun `zolt plan`.", blocker.nextStep());
    }

    @Test
    void blocksPreV7LockfilesBeforeInferringAnyExecToolState() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 6\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.BUILD, Optional.empty());

        assertEquals(List.of("lockfile"), plan.nodes().stream().map(PlanNode::id).toList());
        PlanBlocker blocker = blocker(node(plan, "lockfile"), "invalid-lockfile");
        assertTrue(blocker.message().contains("version 6 is older than this Zolt supports (current 7)"));
    }

    @Test
    void plansResourceFilteringAndTestRuntimeDetailsDeterministically() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Map<String, ResourceTokenSettings> tokens = new LinkedHashMap<>();
        tokens.put("app.name", ResourceTokenSettings.literal("demo"));
        tokens.put("secret", ResourceTokenSettings.env("SECRET_TOKEN"));
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                true,
                List.of("**/*.properties", "**/*.yaml"),
                ResourceMissingTokenPolicy.KEEP,
                tokens);
        TestRuntimeSettings runtime = new TestRuntimeSettings(
                List.of("-ea", "-Xmx256m"),
                Map.of(
                        "junit.jupiter.execution.parallel.enabled", "false",
                        "feature.enabled", "true"),
                Map.of("CI", "true", "API_TOKEN", "secret"),
                List.of("passed", "failed"));
        BuildSettings build = new BuildSettings(
                "src/main/java",
                List.of("src/main/java", "src/generated/main"),
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java", "src/testSupport/java"),
                List.of("src/test/groovy"),
                null,
                null,
                null,
                List.of("src/main/resources"),
                List.of("src/test/resources"),
                filtering,
                runtime,
                Map.of(),
                BuildMetadataSettings.defaults(),
                List.of(),
                List.of());
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                build);
        Path reports = projectDir.resolve("reports/tests");

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.TEST, Optional.of(reports));

        assertFalse(plan.blocked());
        assertEquals(
                List.of(
                        "filtering: enabled",
                        "filterIncludes: [**/*.properties, **/*.yaml]",
                        "missingTokens: keep",
                        "tokens: [app.name, secret]"),
                node(plan, "process-main-resources").details());
        assertEquals(
                List.of(
                        "filtering: enabled",
                        "filterIncludes: [**/*.properties, **/*.yaml]",
                        "missingTokens: keep",
                        "tokens: [app.name, secret]"),
                node(plan, "process-test-resources").details());
        assertEquals(
                List.of("src/main/java", "src/generated/main"),
                node(plan, "compile-main").inputs());
        assertEquals(
                List.of("target/classes", "src/test/java", "src/testSupport/java", "src/test/groovy"),
                node(plan, "compile-tests").inputs());
        assertEquals(
                List.of(
                        "javaTestRoots: [src/test/java, src/testSupport/java]",
                        "groovyTestRoots: [src/test/groovy]"),
                node(plan, "compile-tests").details());
        assertEquals(List.of(reports.toString()), node(plan, "run-tests").outputs());
        assertEquals(
                List.of(
                        "jvmArgs: 2",
                        "systemProperties: [feature.enabled, junit.jupiter.execution.parallel.enabled]",
                        "environment: [API_TOKEN, CI] (values redacted)",
                        "events: passed,failed"),
                node(plan, "run-tests").details());
    }

    @Test
    void surfacesReadyTestRuntimeToolchainInRunTestsNode() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "17", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.TEST,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TestRuntimePlan("17", true, Optional.empty(), Optional.empty())));

        assertFalse(plan.blocked());
        PlanNode runTests = node(plan, "run-tests");
        assertEquals(PlanNodeStatus.READY, runTests.status());
        assertTrue(runTests.details().contains("testRuntimeJava: 17 ([toolchain.java.test])"));
    }

    @Test
    void blocksTestRuntimeToolchainThatIsNotReady() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.TEST,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TestRuntimePlan(
                        "17",
                        false,
                        Optional.of("Test runtime Java 17 is older than the compiled [project].java release 21;"
                                + " tests would fail with UnsupportedClassVersionError."),
                        Optional.of("Set [toolchain.java.test].version to 21 or newer, or lower [project].java."))));

        assertTrue(plan.blocked());
        PlanNode runTests = node(plan, "run-tests");
        assertEquals(PlanNodeStatus.BLOCKED, runTests.status());
        PlanBlocker blocker = blocker(runTests, "test-runtime-toolchain");
        assertTrue(blocker.message().contains("UnsupportedClassVersionError"));
    }

    @Test
    void plansCoveragePackageAndPublishOutputsUnderConfiguredOutputRoot() {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        new BuildSettings(
                                "src/main/java",
                                "src/test/java",
                                ".zolt/build",
                                ".zolt/build/classes",
                                ".zolt/build/test-classes"))
                .withPackageSettings(new PackageSettings(
                        PackageMode.THIN,
                        true,
                        true,
                        true,
                        PublicationMetadata.empty()));

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.CI, Optional.empty());

        PlanNode coverage = node(plan, "coverage");
        assertEquals(List.of(".zolt/build/test-classes", "zolt.lock"), coverage.inputs());
        assertEquals(List.of(".zolt/build/coverage"), coverage.outputs());

        PlanNode packageNode = node(plan, "assemble-package");
        assertEquals(List.of(
                ".zolt/build/demo-1.0.0.jar",
                ".zolt/build/demo-1.0.0-sources.jar",
                ".zolt/build/demo-1.0.0-javadoc.jar",
                ".zolt/build/demo-1.0.0-tests.jar"), packageNode.outputs());

        PlanNode publish = node(plan, "publish-dry-run");
        assertTrue(publish.inputs().contains(".zolt/build/demo-1.0.0.jar"));
        assertTrue(publish.inputs().contains("zolt.lock"));
        assertEquals(List.of(".zolt/build/publish"), publish.outputs());
    }

    @Test
    void buildTargetStopsBeforeTestCoveragePackageAndPublishNodes() {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.BUILD, Optional.empty());

        assertTrue(plan.nodes().stream().anyMatch(node -> node.id().equals("compile-main")));
        assertTrue(plan.nodes().stream().noneMatch(node -> node.id().equals("compile-tests")));
        assertTrue(plan.nodes().stream().noneMatch(node -> node.id().equals("run-tests")));
        assertTrue(plan.nodes().stream().noneMatch(node -> node.id().equals("coverage")));
        assertTrue(plan.nodes().stream().noneMatch(node -> node.id().equals("assemble-package")));
        assertTrue(plan.nodes().stream().noneMatch(node -> node.id().equals("publish-dry-run")));
    }

    @Test
    void regularWarPackageUsesWarExtensionWithoutMainClassBlocker() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(PackageMode.WAR));

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.PACKAGE, Optional.empty());

        PlanNode packageNode = node(plan, "assemble-package");
        assertFalse(plan.blocked());
        assertEquals(PlanNodeStatus.READY, packageNode.status());
        assertEquals(List.of("target/demo-1.0.0.war"), packageNode.outputs());
        assertTrue(packageNode.blockers().isEmpty());
    }

    @Test
    void plansGeneratedSourcesSkippedResourcesAndBlankOutputRootFallback() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        writeFile("src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        writeFile("src/test/fixtures/schema.json", "{}\n");
        writeFile("target/generated/sources/api/com/example/Api.java", "package com.example; interface Api {}\n");
        writeFile(
                "target/generated/test-sources/fixtures/com/example/Fixture.java",
                "package com.example; final class Fixture {}\n");
        GeneratedSourceStep mainGenerated = new GeneratedSourceStep(
                "api",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated/sources/api",
                List.of("src/main/openapi/api.yaml"),
                true,
                false);
        GeneratedSourceStep testGenerated = new GeneratedSourceStep(
                "fixtures",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated/test-sources/fixtures",
                List.of("src/test/fixtures/schema.json"),
                true,
                false);
        BuildSettings build = new BuildSettings(
                "src/main/java",
                List.of("src/main/java"),
                "src/test/java",
                " ",
                "build/classes",
                "build/test-classes",
                List.of("src/test/java"),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(),
                Map.of(),
                BuildMetadataSettings.defaults(),
                List.of(mainGenerated),
                List.of(testGenerated));
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                build);

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.CI, Optional.empty());

        assertFalse(plan.blocked());
        assertEquals(PlanNodeStatus.READY, node(plan, "generate-main-api").status());
        assertEquals(PlanNodeStatus.READY, node(plan, "generate-test-fixtures").status());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "process-main-resources").status());
        assertEquals(List.of("filtering: disabled"), node(plan, "process-main-resources").details());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "process-test-resources").status());
        assertEquals(List.of("filtering: disabled"), node(plan, "process-test-resources").details());
        assertEquals(
                List.of("src/main/java", "target/generated/sources/api"),
                node(plan, "compile-main").inputs());
        assertEquals(
                List.of("build/classes", "src/test/java", "target/generated/test-sources/fixtures"),
                node(plan, "compile-tests").inputs());
        assertEquals(List.of(), node(plan, "run-tests").outputs());
        assertEquals(List.of("target/coverage"), node(plan, "coverage").outputs());
        assertEquals(List.of("target/demo-1.0.0.jar"), node(plan, "assemble-package").outputs());
        assertEquals(List.of("target/publish"), node(plan, "publish-dry-run").outputs());
    }

    @Test
    void plansSpringBootPackageWhenMainClassIsConfigured() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "demo",
                                "1.0.0",
                                "com.example",
                                "21",
                                Optional.of("com.example.DemoApplication")),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.PACKAGE, Optional.empty());

        PlanNode packageNode = node(plan, "assemble-package");
        assertFalse(plan.blocked());
        assertEquals(PlanNodeStatus.READY, packageNode.status());
        assertEquals(List.of("target/demo-1.0.0.jar"), packageNode.outputs());
        assertEquals(List.of("mode: spring-boot"), packageNode.details());
        assertTrue(packageNode.blockers().isEmpty());
    }

    @Test
    void blocksSpringBootWarPackageWithoutMainClassAndKeepsWarOutput() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT_WAR));

        BuildPlan plan = service.plan(projectDir, config, PlanTarget.PACKAGE, Optional.empty());

        PlanNode packageNode = node(plan, "assemble-package");
        assertTrue(plan.blocked());
        assertEquals(PlanNodeStatus.BLOCKED, packageNode.status());
        assertEquals(List.of("target/demo-1.0.0.war"), packageNode.outputs());
        PlanBlocker blocker = blocker(packageNode, "missing-main-class");
        assertEquals("Spring Boot package modes require [project].main.", blocker.message());
        assertEquals("Add [project].main to zolt.toml.", blocker.nextStep());
    }

    private static PlanNode node(BuildPlan plan, String id) {
        return plan.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static PlanBlocker blocker(PlanNode node, String code) {
        return node.blockers().stream()
                .filter(blocker -> blocker.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
