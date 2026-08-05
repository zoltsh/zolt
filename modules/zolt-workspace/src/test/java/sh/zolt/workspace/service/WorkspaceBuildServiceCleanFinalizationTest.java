package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.provenance.GitProvenance;
import sh.zolt.resolve.ResolveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceBuildServiceCleanFinalizationTest {
    private static final String COMMIT_SHA =
            "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    private Path tempDir;

    @Test
    void cleanMemberRestoresDeletedBuildInfoWithoutInvokingCompilePipeline()
            throws IOException {
        prepareMetadataWorkspace();
        WorkspaceBuildService service =
                new WorkspaceBuildService(new ResolveService(), provenanceSource());
        service.build(tempDir, cacheRoot(), false);
        Path buildInfo =
                tempDir.resolve("apps/api/target/classes/META-INF/build-info.properties");
        Files.delete(buildInfo);

        WorkspaceBuildResult repaired =
                service.build(tempDir, cacheRoot(), false);

        assertTrue(Files.isRegularFile(buildInfo));
        assertTrue(Files.readString(buildInfo).contains("build.name=api"));
        assertEquals(0, repaired.executionMetrics().memberPipelineInvocations());
        assertEquals(1, repaired.mainCompilationSkippedCount());
    }

    @Test
    void cleanMemberRestoresDeletedGitPropertiesWithoutInvokingCompilePipeline()
            throws IOException {
        prepareMetadataWorkspace();
        WorkspaceBuildService service =
                new WorkspaceBuildService(new ResolveService(), provenanceSource());
        service.build(tempDir, cacheRoot(), false);
        Path gitProperties =
                tempDir.resolve("apps/api/target/classes/git.properties");
        Files.delete(gitProperties);

        WorkspaceBuildResult repaired =
                service.build(tempDir, cacheRoot(), false);

        assertTrue(Files.isRegularFile(gitProperties));
        assertTrue(Files.readString(gitProperties).contains("git.commit.id=" + COMMIT_SHA));
        assertEquals(0, repaired.executionMetrics().memberPipelineInvocations());
        assertEquals(1, repaired.mainCompilationSkippedCount());
    }

    @Test
    void cleanMemberFailsWhenItsManagedJdkIsNoLongerAvailable()
            throws IOException {
        prepareWorkspace("");
        AtomicInteger detections = new AtomicInteger();
        JdkChecker checker = requiredVersion -> detections.incrementAndGet() == 1
                ? availableJdk(requiredVersion)
                : missingJdk(requiredVersion);
        WorkspaceBuildService service =
                new WorkspaceBuildService(checker, new ResolveService());
        service.build(tempDir, cacheRoot(), false);

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.build(tempDir, cacheRoot(), false));

        assertTrue(exception.getMessage().contains("JDK check failed"));
        assertTrue(
                exception.getMessage().contains("apps/api"),
                "the failure names the member whose toolchain could not be used");
        assertEquals(2, detections.get());
    }

    @Test
    void toolchainConfigurationChangeInvokesCanonicalMemberPipeline()
            throws IOException {
        prepareWorkspace("""

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                """.formatted(currentJavaMajorVersion()));
        WorkspaceBuildService service =
                new WorkspaceBuildService(new ResolveService(), provenanceSource());
        service.build(tempDir, cacheRoot(), false);
        Path config = tempDir.resolve("apps/api/zolt.toml");
        Files.writeString(
                config,
                Files.readString(config).replace(
                        "distribution = \"temurin\"",
                        "distribution = \"graalvm-community\""));

        WorkspaceBuildResult rebuilt =
                service.build(tempDir, cacheRoot(), false);

        assertEquals(1, rebuilt.executionMetrics().memberPipelineInvocations());
        assertEquals(0, rebuilt.mainCompilationSkippedCount());
    }

    private void prepareMetadataWorkspace() throws IOException {
        prepareWorkspace("""

                [build.metadata]
                buildInfo = true
                git = true
                reproducible = true
                """);
    }

    private void prepareWorkspace(String extraToml) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        Path member = tempDir.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                %s""".formatted(currentJavaMajorVersion(), extraToml));
        Path source = member.resolve("src/main/java/com/acme/api/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme.api;

                public final class Api {
                }
                """);
    }

    private Path cacheRoot() {
        return tempDir.resolve("cache");
    }

    private static BuildProvenanceSource provenanceSource() {
        return (projectRoot, environment, clock) -> new BuildProvenance(
                new GitProvenance(
                        Optional.of(COMMIT_SHA),
                        Optional.of("0123456789ab"),
                        Optional.of("main"),
                        false,
                        Optional.empty()),
                Instant.parse("2026-07-29T00:00:00Z"),
                "test",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                Optional.empty());
    }

    private static JdkStatus availableJdk(String requiredVersion) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return new JdkStatus(
                Optional.of(javaHome),
                Optional.of(javaHome.resolve("bin/java")),
                Optional.of(javaHome.resolve("bin/javac")),
                Optional.of(javaHome.resolve("bin/jar")),
                Optional.of(requiredVersion),
                requiredVersion);
    }

    private static JdkStatus missingJdk(String requiredVersion) {
        return new JdkStatus(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                requiredVersion);
    }

    private static String currentJavaMajorVersion() {
        return String.valueOf(Runtime.version().feature());
    }
}
