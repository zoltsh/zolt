package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.lockfile.LockPackageCachePath;
import sh.zolt.lockfile.LockPackagePathKind;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceGeneratedSourceFingerprintTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void generatedSourceChangeInvalidatesMainBuildFingerprint() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path processorJar = cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        Files.createDirectories(processorJar.getParent());
        Files.copy(
                AnnotationProcessorFixture.processorJar(projectDir.resolve("processor-work")),
                processorJar,
                StandardCopyOption.REPLACE_EXISTING);
        writeProcessorLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);
        buildService.build(projectDir, config(), cacheRoot);
        Files.writeString(
                projectDir.resolve("target/generated/sources/annotations/com/example/GeneratedMessage.java"),
                "package com.example; public final class GeneratedMessage { public static String value() { return \"tampered\"; } }\n");

        BuildResult result = buildService.build(projectDir, config(), cacheRoot);

        assertFalse(result.mainCompilationSkipped());
    }

    @Test
    void processorClasspathCorruptionFailsClosed() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path processorJar = cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        Files.createDirectories(processorJar.getParent());
        Files.copy(
                AnnotationProcessorFixture.processorJar(projectDir.resolve("processor-work")),
                processorJar,
                StandardCopyOption.REPLACE_EXISTING);
        writeProcessorLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);
        buildService.build(projectDir, config(), cacheRoot);
        Path lockedProcessor = LockPackageCachePath.path(
                        new ZoltLockfileReader().read(projectDir.resolve("zolt.lock")).packages().getFirst(),
                        LockPackagePathKind.JAR)
                .orElseThrow()
                .resolveWithin(cacheRoot);
        appendJarEntry(lockedProcessor, "zolt-marker.txt", "changed\n");

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> buildService.build(projectDir, config(), cacheRoot));

        assertTrue(exception.getMessage().contains("Cached jar integrity check failed"));
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

    private void writeProcessorLockfile() throws IOException {
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"),
                projectDir.resolve("cache"),
                """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:processor"
                version = "1.0.0"
                lane = "processor"
                resolvedScope = "processor"

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
    }

    private static void appendJarEntry(Path jar, String entryName, String content) throws IOException {
        Path tempJar = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (JarInputStream input = new JarInputStream(Files.newInputStream(jar));
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(tempJar))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                output.putNextEntry(new JarEntry(entry.getName()));
                input.transferTo(output);
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.move(tempJar, jar, StandardCopyOption.REPLACE_EXISTING);
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
