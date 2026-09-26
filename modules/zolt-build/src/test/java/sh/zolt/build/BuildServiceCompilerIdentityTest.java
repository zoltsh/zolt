package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceCompilerIdentityTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheHome;

    @Test
    void sameMajorCompilerChangeInvalidatesWarmFingerprintAndIncrementalState() throws IOException {
        MutableJdkChecker checker = new MutableJdkChecker("compiler-a");
        BuildService service = new BuildService(checker);
        writeProject("one");

        BuildResult first = service.build(projectDir, config(false), artifactCache());
        BuildResult warm = service.build(projectDir, config(false), artifactCache());
        checker.identity = "compiler-b";
        BuildResult changed = service.build(projectDir, config(false), artifactCache());

        assertFalse(first.mainCompilationSkipped());
        assertTrue(warm.mainCompilationSkipped());
        assertFalse(changed.mainCompilationSkipped());
        assertEquals("full", changed.mainCompilationMode());
        assertEquals("compiler-identity-changed", changed.mainIncrementalFallbackReason());
    }

    @Test
    void sameMajorCompilerChangeCannotRestoreAnotherCompilerCacheEntryInHostMode() throws IOException {
        MutableJdkChecker checker = new MutableJdkChecker("compiler-a");
        BuildCacheSettings settings = new BuildCacheSettings(
                true,
                cacheHome.resolve("build-cache"),
                0L);
        BuildService service = new BuildService(checker)
                .withBuildCache(BuildCacheService.create(settings, "test-version"));
        ProjectConfig config = config(true);
        writeProject("one");

        BuildResult first = service.build(projectDir, config, artifactCache());
        wipeTarget();
        BuildResult restored = service.build(projectDir, config, artifactCache());
        wipeTarget();
        checker.identity = "compiler-b";
        BuildResult changed = service.build(projectDir, config, artifactCache());

        assertEquals("stored", first.mainBuildCacheOutcome());
        assertTrue(restored.mainCompilationRestored());
        assertEquals("restored", restored.mainBuildCacheOutcome());
        assertFalse(changed.mainCompilationRestored());
        assertEquals("stored", changed.mainBuildCacheOutcome());
    }

    private void writeProject(String value) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static String value() {
                        return "%s";
                    }
                }
                """.formatted(value));
    }

    private void wipeTarget() throws IOException {
        Path target = projectDir.resolve("target");
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    private Path artifactCache() {
        return projectDir.resolve("cache");
    }

    private static ProjectConfig config(boolean hostPlatformApi) {
        ProjectConfig base = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        if (!hostPlatformApi) {
            return base;
        }
        CompilerSettings compiler = new CompilerSettings(
                base.compilerSettings().generatedSources(),
                base.compilerSettings().generatedTestSources(),
                "",
                "",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_HOST,
                "");
        return ProjectConfigs.withDependencySections(
                base.project(),
                base.repositories(),
                base.platforms(),
                base.dependencies(),
                Set.of(),
                base.testDependencies(),
                Set.of(),
                base.annotationProcessors(),
                Set.of(),
                base.testAnnotationProcessors(),
                Set.of(),
                base.build(),
                NativeSettings.defaults(),
                compiler);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }

    private static final class MutableJdkChecker implements JdkChecker {
        private String identity;

        private MutableJdkChecker(String identity) {
            this.identity = identity;
        }

        @Override
        public JdkStatus detect(String requiredVersion) {
            Path javaHome = Path.of(System.getProperty("java.home"));
            return new JdkStatus(
                    Optional.of(javaHome),
                    Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                    Optional.of(currentJavaMajorVersion() + ".0.1"),
                    Optional.of(identity),
                    requiredVersion);
        }

        private static String executable(String name) {
            return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? name + ".exe"
                    : name;
        }
    }
}
