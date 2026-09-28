package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceKotlinJavadocTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void rejectsBeforeReusingOrMutatingPackageOutputs() throws IOException {
        write(projectDirectory.resolve("src/main/kotlin/com/example/KotlinApi.kt"), """
                package com.example
                class KotlinApi
                """);
        Path classes = projectDirectory.resolve("target/classes");
        write(classes.resolve("com/example/KotlinApi.class"), "compiled Kotlin");
        write(projectDirectory.resolve("zolt.lock"), "version = 7\n");

        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [package]
                sources = true
                javadoc = true
                """);
        PackagePlan plan = new PackagePlanService().plan(projectDirectory, config);
        Path primaryJar = plan.archivePath();
        Path sourcesJar = projectDirectory.resolve("target/demo-0.1.0-sources.jar");
        Path javadocJar = projectDirectory.resolve("target/demo-0.1.0-javadoc.jar");
        Path documentation = projectDirectory.resolve("target/javadoc/com/example/Existing.html");
        byte[] primaryBytes = write(primaryJar, "existing primary JAR");
        byte[] sourcesBytes = write(sourcesJar, "existing sources JAR");
        byte[] javadocBytes = write(javadocJar, "existing Javadoc JAR");
        if (plan.runtimeClasspathPath().isPresent()) {
            write(plan.runtimeClasspathPath().orElseThrow(), "existing runtime classpath");
        }
        write(documentation, "existing documentation");
        BuildResult buildResult = new BuildResult(Optional.empty(), 1, 0, classes, "");
        PackageResult priorResult = new PackageResult(
                buildResult,
                config.packageSettings().mode(),
                primaryJar,
                plan.runtimeClasspathPath(),
                1,
                false);
        List<PackageArtifact> priorArtifacts = List.of(
                new PackageArtifact("sources", sourcesJar, 1),
                new PackageArtifact("javadoc", javadocJar, 0));
        Path evidence = new PackageEvidenceManifestWriter().write(
                projectDirectory,
                config,
                plan,
                priorResult,
                priorArtifacts);
        assertTrue(
                new PackageEvidenceVerifier().verify(projectDirectory, plan, evidence).valid(),
                "fixture must represent a package result eligible for reuse");
        byte[] evidenceBytes = Files.readAllBytes(evidence);

        PackageException exception = assertThrows(
                PackageException.class,
                () -> new PackageService().packageJar(
                        projectDirectory,
                        config,
                        buildResult));

        assertTrue(exception.getMessage().contains("Kotlin/Dokka Javadoc publication is outside"));
        assertArrayEquals(primaryBytes, Files.readAllBytes(primaryJar));
        assertArrayEquals(sourcesBytes, Files.readAllBytes(sourcesJar));
        assertArrayEquals(javadocBytes, Files.readAllBytes(javadocJar));
        assertArrayEquals(evidenceBytes, Files.readAllBytes(evidence));
        assertEquals("existing documentation", Files.readString(documentation));
    }

    private static byte[] write(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return bytes;
    }
}
