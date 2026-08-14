package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.project.SpringBootSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void mainCompilationUsesProcessorClasspathAndGeneratedSources() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path processorJar = cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        Files.createDirectories(processorJar.getParent());
        Files.copy(
                AnnotationProcessorFixture.processorJar(projectDir.resolve("processor-work")),
                processorJar,
                StandardCopyOption.REPLACE_EXISTING);
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), cacheRoot);

        assertEquals(1, result.sourceCount());
        assertEquals("full", result.mainCompilationMode());
        assertEquals("processor-metadata-missing", result.mainIncrementalFallbackReason());
        assertTrue(Files.exists(projectDir.resolve(
                "target/generated/sources/annotations/com/example/GeneratedMessage.java")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/GeneratedMessage.class")));
        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/classes/.zolt-incremental-main.state"))
                .orElseThrow();
        assertTrue(state.fallbackReasons().contains("processor-metadata-missing"));
    }

    @Test
    void isolatingProcessorWithoutWorkerAttributionStaysOnFullRecompile() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path processorJar = cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        Files.createDirectories(processorJar.getParent());
        Files.copy(
                AnnotationProcessorFixture.isolatingProcessorJar(projectDir.resolve("processor-work")),
                processorJar,
                StandardCopyOption.REPLACE_EXISTING);
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);

        BuildResult first = buildService.build(projectDir, config(), cacheRoot);
        assertEquals("full", first.mainCompilationMode());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/GeneratedMessage.class")));

        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value() + "!";
                    }
                }
                """);
        BuildResult second = buildService.build(projectDir, config(), cacheRoot);

        // No javac worker is reachable in this test, so the isolating processor's outputs cannot be
        // attributed and the build conservatively stays on the full-recompile path.
        assertEquals("full", second.mainCompilationMode());
        assertEquals("processor-generated-outputs-untracked", second.mainIncrementalFallbackReason());
        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/classes/.zolt-incremental-main.state"))
                .orElseThrow();
        assertFalse(state.processorAttributionComplete());
    }

    @Test
    void failsBeforeCompilationWhenCachedCompileJarDoesNotMatchLockfileHash() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path jar = cacheRoot.resolve("com/example/compile-lib/1.0.0/compile-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "corrupted jar bytes");
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:compile-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/compile-lib/1.0.0/compile-lib-1.0.0.jar"
                jarSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                dependencies = []
                """);
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> buildService.build(projectDir, config(), cacheRoot));

        assertTrue(exception.getMessage().contains(
                "Cached jar integrity check failed for com.example:compile-lib:1.0.0"));
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void generatesReproducibleBuildInfoMetadata() throws IOException {
        writeLockfile("version = 1\n");
        ProjectConfig config = config().withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                new BuildMetadataSettings(true, false, true)));

        BuildResult result = buildService.build(projectDir, config, projectDir.resolve("cache"));

        assertEquals(0, result.sourceCount());
        assertEquals(1, result.resourceCount());
        assertEquals("""
                build.artifact=demo
                build.group=com.example
                build.name=demo
                build.time=1970-01-01T00:00:00Z
                build.version=0.1.0
                """, Files.readString(projectDir.resolve("target/classes/META-INF/build-info.properties")));
    }

    @Test
    void springBootNativeRequiresLockedAotTooling() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        ProjectConfig config = config().withFrameworkSettings(new FrameworkSettings(
                new SpringBootSettings(true),
                QuarkusSettings.defaults()));

        BuildException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BuildException.class,
                () -> buildService.build(projectDir, config, projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Spring Boot AOT processing requires locked tool artifacts"));
        assertTrue(exception.getMessage().contains("tool-spring-aot"));
        assertTrue(exception.getMessage().contains("Run `zolt resolve`"));
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
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
    }

    private Path source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void writeLockfile(String content) throws IOException {
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), projectDir.resolve("cache"), content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
