package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.GroovyObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** End-to-end proof that Zolt's production build path performs real Java/Groovy joint compilation. */
final class BuildServiceGroovyMainIntegrationTest {
    private static final String GROOVY_VERSION = "4.0.22";
    private static final String GROOVY_JAR_SHA256 =
            "f9d8bd4d65852c18194e353c77f3d2c23e0013856951c5430ba56972d2f67a1e";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheHome;

    @Test
    void jointlyCompilesCircularSourcesAndSafelyReusesTheirOutput() throws Exception {
        Path artifactCache = prepareGroovyArtifactCache();
        writeLockfile(GROOVY_VERSION);
        Path javaSource = source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                public final class JavaApi {
                    public static String prefix() {
                        return "java";
                    }

                    public static String callGroovy() {
                        return GroovyApi.message();
                    }
                }
                """);
        Path groovySource = source("src/main/java/com/example/GroovyApi.groovy", """
                package com.example

                final class GroovyApi {
                    static String message() {
                        JavaApi.prefix() + "-groovy"
                    }
                }
                """);
        Path obsoleteSource = source("src/main/java/com/example/Obsolete.groovy", """
                package com.example

                final class Obsolete {
                    static Closure<String> supplier() {
                        { -> "obsolete" }
                    }
                }
                """);
        BuildService service = cacheEnabledService();

        BuildResult first = service.build(projectDir, config(), artifactCache);

        assertEquals(3, first.sourceCount());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("groovy-main-sources", first.mainIncrementalFallbackReason());
        assertEquals("stored", first.mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
        assertTrue(Files.isRegularFile(classFile("GroovyApi.class")));
        assertTrue(classFiles().stream().anyMatch(path -> path.startsWith("com/example/Obsolete")));
        assertEquals("java-groovy", invokeJavaApi(artifactCache));

        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/classes/.zolt-incremental-main.state"))
                .orElseThrow();
        assertTrue(state.fallbackReasons().contains("groovy-main-sources"));
        assertTrue(state.classes().stream()
                .anyMatch(record -> "com.example.GroovyApi".equals(record.binaryName())));

        BuildResult warm = service.build(projectDir, config(), artifactCache);
        assertTrue(warm.mainCompilationSkipped());
        assertEquals(3, warm.sourceCount());

        wipeTarget();
        BuildResult restored = service.build(projectDir, config(), artifactCache);
        assertTrue(restored.mainCompilationRestored());
        assertEquals(3, restored.sourceCount());
        assertEquals("java-groovy", invokeJavaApi(artifactCache));

        Files.delete(obsoleteSource);
        BuildResult rebuilt = service.build(projectDir, config(), artifactCache);
        assertFalse(rebuilt.mainCompilationSkipped());
        assertFalse(rebuilt.mainCompilationRestored());
        assertEquals("full", rebuilt.mainCompilationMode());
        assertEquals("groovy-main-sources", rebuilt.mainIncrementalFallbackReason());
        assertEquals(2, rebuilt.sourceCount());
        assertFalse(classFiles().stream().anyMatch(path -> path.startsWith("com/example/Obsolete")));
        assertEquals("java-groovy", invokeJavaApi(artifactCache));
        assertTrue(Files.isRegularFile(javaSource));
        assertTrue(Files.isRegularFile(groovySource));
    }

    @Test
    void configuredCompilerSkewFailsBeforeExistingOutputIsCleaned() throws Exception {
        Path artifactCache = prepareGroovyArtifactCache();
        writeLockfile("4.0.23");
        source("src/main/java/com/example/Main.groovy", """
                package com.example

                final class Main {
                }
                """);
        Path staleClass = projectDir.resolve("target/classes/com/example/StillHere.class");
        Files.createDirectories(staleClass.getParent());
        Files.write(staleClass, new byte[] {1});

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> cacheEnabledService().build(projectDir, config(), artifactCache));

        assertTrue(exception.getMessage().contains(
                "configured version `4.0.22` does not match zolt.lock tool root version `4.0.23`"));
        assertTrue(Files.exists(staleClass));
    }

    private BuildService cacheEnabledService() {
        BuildCacheSettings settings = new BuildCacheSettings(
                true, cacheHome.resolve("build-cache"), 0L);
        return new BuildService().withBuildCache(
                BuildCacheService.create(settings, "groovy-main-integration"));
    }

    private Path prepareGroovyArtifactCache() throws IOException, URISyntaxException {
        Path groovyJar = Path.of(GroovyObject.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path cached = projectDir.resolve("cache").resolve(groovyJarCachePath());
        Files.createDirectories(cached.getParent());
        Files.copy(groovyJar, cached, StandardCopyOption.REPLACE_EXISTING);
        return projectDir.resolve("cache");
    }

    private void writeLockfile(String toolVersion) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.apache.groovy:groovy"
                version = "4.0.22"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "org.apache.groovy:groovy"
                version = "4.0.22"
                source = "central"
                scope = "compile"
                direct = true
                jar = "blobs/v2/sha256/f9d8bd4d65852c18194e353c77f3d2c23e0013856951c5430ba56972d2f67a1e/groovy-4.0.22.jar"
                jarSha256 = "f9d8bd4d65852c18194e353c77f3d2c23e0013856951c5430ba56972d2f67a1e"
                dependencies = []

                [[package]]
                id = "org.apache.groovy:groovy"
                version = "%s"
                source = "central"
                scope = "tool-groovy"
                direct = true
                jar = "blobs/v2/sha256/f9d8bd4d65852c18194e353c77f3d2c23e0013856951c5430ba56972d2f67a1e/groovy-4.0.22.jar"
                jarSha256 = "f9d8bd4d65852c18194e353c77f3d2c23e0013856951c5430ba56972d2f67a1e"
                dependencies = []
                """.formatted(toolVersion));
    }

    private String invokeJavaApi(Path artifactCache) throws Exception {
        Path groovyJar = artifactCache.resolve(groovyJarCachePath());
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {
                    projectDir.resolve("target/classes").toUri().toURL(),
                    groovyJar.toUri().toURL()
                },
                ClassLoader.getPlatformClassLoader())) {
            Class<?> javaApi = Class.forName("com.example.JavaApi", true, loader);
            return (String) javaApi.getMethod("callGroovy").invoke(null);
        }
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private List<String> classFiles() throws IOException {
        Path output = projectDir.resolve("target/classes");
        try (Stream<Path> paths = Files.walk(output)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(output::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
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

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "groovy-main"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.JavaApi"

                [toolchain.groovy]
                version = "4.0.22"

                [dependencies]
                "org.apache.groovy:groovy" = "4.0.22"
                """.formatted(currentJavaMajorVersion()));
    }

    private static Path groovyJarCachePath() {
        return Path.of("blobs/v2/sha256")
                .resolve(GROOVY_JAR_SHA256)
                .resolve("groovy-" + GROOVY_VERSION + ".jar");
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
