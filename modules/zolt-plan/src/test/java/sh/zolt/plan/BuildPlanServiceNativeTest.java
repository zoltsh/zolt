package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.project.SpringBootSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPlanServiceNativeTest {
    private final BuildPlanService service = new BuildPlanService();

    @TempDir
    private Path projectDir;

    @Test
    void plansReadySpringBootNativeInputsWithoutExecutingTools() throws IOException {
        ProjectConfig config = springBootNativeConfig();
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        writeLockfileWithSpringAotTooling();
        writeSpringAotOutput(FileTime.fromMillis(2_000));
        writeInputFiles(FileTime.fromMillis(1_000));

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        assertFalse(plan.blocked());
        assertEquals(PlanNodeStatus.READY, node(plan, "spring-boot-native-intent").status());
        assertEquals(PlanNodeStatus.READY, node(plan, "spring-aot-tooling").status());
        assertEquals(PlanNodeStatus.READY, node(plan, "spring-aot-output").status());
        assertEquals(PlanNodeStatus.READY, node(plan, "native-image-executable").status());
        assertTrue(node(plan, "spring-aot-output").details().contains("freshness: present"));
        assertTrue(node(plan, "native-image").details().contains("nativeArgs: [--no-fallback]"));
        assertEquals(List.of("target/demo-1.0.0.jar"), node(plan, "native-package-input").outputs());
    }

    @Test
    void plansSpringBootNativeBlockersForMissingToolingStaleAotAndMissingNativeImage() throws IOException {
        ProjectConfig config = springBootNativeConfig();
        writeLockfileWithoutSpringAotTooling();
        writeSpringAotOutput(FileTime.fromMillis(1_000));
        writeInputFiles(FileTime.fromMillis(2_000));

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(projectDir.resolve("missing/native-image")));

        assertTrue(plan.blocked());
        assertBlocker(node(plan, "spring-aot-tooling"), "missing-spring-aot-tooling");
        assertBlocker(node(plan, "spring-aot-output"), "stale-spring-aot-output");
        assertBlocker(node(plan, "native-image-executable"), "missing-native-image");
        assertTrue(node(plan, "spring-aot-output").details().contains("freshness: stale"));
    }

    @Test
    void nativePlanBlocksUnsupportedBaselinesAndFrameworkBoundaries() {
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("org.springframework.boot:spring-boot-starter-web", "3.2.0");
        dependencies.put("io.micronaut:micronaut-core", "4.10.12");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "mixed",
                                "1.0.0",
                                "com.example",
                                "17",
                                Optional.of("com.example.MixedApplication")),
                        Map.of(),
                        dependencies,
                        Map.of(),
                        BuildSettings.defaults())
                .withFrameworkSettings(new FrameworkSettings(new SpringBootSettings(true), QuarkusSettings.defaults()))
                .withPackageSettings(new PackageSettings(PackageMode.QUARKUS));

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(projectDir.resolve("missing/native-image")));

        assertBlocker(node(plan, "spring-boot-native-intent"), "unsupported-java-baseline");
        assertBlocker(node(plan, "spring-boot-native-intent"), "unsupported-spring-boot-baseline");
        assertBlocker(node(plan, "native-support-boundary"), "unsupported-micronaut-native");
        assertBlocker(node(plan, "native-support-boundary"), "unsupported-quarkus-native");
    }

    @Test
    void nativePlanDoesNotRequireSpringAotForPlainJavaProjects() throws IOException {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "plain",
                        "1.0.0",
                        "com.example",
                        "21",
                        Optional.of("com.example.Main")),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        assertFalse(plan.blocked());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "spring-aot-tooling").status());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "spring-aot-output").status());
        assertEquals(PlanNodeStatus.READY, node(plan, "native-package-input").status());
    }

    @Test
    void nativePlanBlocksSpringBootProjectWhenNativeFlagIsDisabled() throws IOException {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "1.0.0",
                        "com.example",
                        "21",
                        Optional.of("com.example.DemoApplication")),
                Map.of(),
                Map.of("org.springframework.boot:spring-boot-starter-web", "3.3.6"),
                Map.of(),
                BuildSettings.defaults());
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        PlanNode intent = node(plan, "spring-boot-native-intent");
        assertTrue(plan.blocked());
        assertEquals(PlanNodeStatus.BLOCKED, intent.status());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "spring-aot-tooling").status());
        assertEquals(PlanNodeStatus.SKIPPED, node(plan, "spring-aot-output").status());
        PlanBlocker blocker = blocker(intent, "spring-boot-native-disabled");
        assertEquals(
                "Spring Boot native images require `[framework.springBoot.native] enabled = true`.",
                blocker.message());
        assertEquals(
                "Set `[package].mode = \"spring-boot\"`, run `zolt resolve`, then use `zolt package` or `zolt run` for JVM apps, "
                        + "or enable the typed Spring Boot native path.",
                blocker.nextStep());
    }

    @Test
    void nativePlanReportsInvalidLockfileForSpringAotTooling() throws IOException {
        ProjectConfig config = springBootNativeConfig();
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = [\n");

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        assertEquals(List.of("lockfile"), plan.nodes().stream().map(PlanNode::id).toList());
        PlanBlocker blocker = blocker(node(plan, "lockfile"), "invalid-lockfile");
        assertTrue(blocker.message().startsWith("Could not parse zolt.lock near "));
        assertEquals(
                "Run `zolt resolve` to regenerate zolt.lock, then rerun `zolt plan`.",
                blocker.nextStep());
    }

    @Test
    void nativePlanBlocksMissingMainClassForPackageInput() throws IOException {
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", "21", Optional.empty()),
                        Map.of(),
                        Map.of("org.springframework.boot:spring-boot-starter-web", "3.3.6"),
                        Map.of(),
                        BuildSettings.defaults())
                .withFrameworkSettings(new FrameworkSettings(new SpringBootSettings(true), QuarkusSettings.defaults()))
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        writeLockfileWithSpringAotTooling();

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        PlanBlocker blocker = blocker(node(plan, "native-package-input"), "missing-main-class");
        assertTrue(plan.blocked());
        assertEquals("Native Image requires [project].main.", blocker.message());
        assertEquals("Add the application main class to zolt.toml.", blocker.nextStep());
    }

    @Test
    void nativePlanBlocksMissingSpringAotOutputWithRelativeDirectory() throws IOException {
        ProjectConfig config = springBootNativeConfig();
        Path nativeImage = projectDir.resolve("bin/native-image");
        Files.createDirectories(nativeImage.getParent());
        Files.writeString(nativeImage, "#!/bin/sh\nexit 0\n");
        assertTrue(nativeImage.toFile().setExecutable(true));
        writeLockfileWithSpringAotTooling();

        BuildPlan plan = service.plan(
                projectDir,
                config,
                PlanTarget.NATIVE,
                Optional.empty(),
                Optional.of(nativeImage));

        PlanNode aot = node(plan, "spring-aot-output");
        PlanBlocker blocker = blocker(aot, "missing-spring-aot-output");
        assertTrue(aot.details().contains("freshness: missing"));
        assertEquals(
                "Spring Boot native AOT output is incomplete under target/spring-aot/main.",
                blocker.message());
        assertEquals(
                "Run `zolt build` after enabling `[framework.springBoot.native] enabled = true`, "
                        + "then rerun the native plan.",
                blocker.nextStep());
    }

    private static PlanNode node(BuildPlan plan, String id) {
        return plan.nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void assertBlocker(PlanNode node, String code) {
        assertTrue(node.blockers().stream().anyMatch(blocker -> blocker.code().equals(code)));
    }

    private static PlanBlocker blocker(PlanNode node, String code) {
        return node.blockers().stream()
                .filter(blocker -> blocker.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig springBootNativeConfig() {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "demo",
                                "1.0.0",
                                "com.example",
                                "21",
                                Optional.of("com.example.DemoApplication")),
                        Map.of(),
                        Map.of("org.springframework.boot:spring-boot-starter-web", "3.3.6"),
                        Map.of(),
                        BuildSettings.defaults(),
                        new NativeSettings("demo-native", "target/native", List.of("--no-fallback")))
                .withFrameworkSettings(new FrameworkSettings(new SpringBootSettings(true), QuarkusSettings.defaults()))
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));
    }

    private void writeLockfileWithSpringAotTooling() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []

                [[package]]
                id = "org.springframework.boot:spring-boot"
                version = "3.3.6"
                source = "maven-central"
                scope = "tool-spring-aot"
                direct = false
                dependencies = []
                """);
    }

    private void writeLockfileWithoutSpringAotTooling() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.springframework.boot:spring-boot-starter-web"
                version = "3.3.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """);
    }

    private void writeSpringAotOutput(FileTime time) throws IOException {
        Path sources = projectDir.resolve("target/spring-aot/main/sources/com/example/Demo__BeanDefinitions.java");
        Path classes = projectDir.resolve("target/spring-aot/main/classes/com/example/Demo__BeanDefinitions.class");
        Path metadata = projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/com/example/demo");
        Path reflection = metadata.resolve("reflect-config.json");
        Path reachability = metadata.resolve("reachability-metadata.json");
        Files.createDirectories(sources.getParent());
        Files.createDirectories(classes.getParent());
        Files.createDirectories(metadata);
        Files.writeString(sources, "package com.example; final class Demo__BeanDefinitions {}\n");
        Files.writeString(classes, "class-bytes");
        Files.writeString(reflection, "[]\n");
        Files.writeString(reachability, "{}\n");
        for (Path path : List.of(sources, classes, reflection, reachability)) {
            Files.setLastModifiedTime(path, time);
        }
    }

    private void writeInputFiles(FileTime time) throws IOException {
        Path classFile = projectDir.resolve("target/classes/com/example/DemoApplication.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(classFile, "class-bytes");
        Files.setLastModifiedTime(projectDir.resolve("zolt.toml"), time);
        Files.setLastModifiedTime(classFile, time);
    }
}
