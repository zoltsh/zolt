package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin 1.4 inline-class mangling compatibility. */
final class BuildServiceKotlinLegacyInlineClassManglingIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesInlineClassMethodLinkageAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        String baselineMethod = assertMangling(artifacts.applicationClasspath());
        OutputBytes baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult legacy = build(service, true);
        assertFalse(legacy.mainCompilationSkipped());
        String legacyMethod = assertMangling(artifacts.applicationClasspath());
        assertNotEquals(baselineMethod, legacyMethod);
        assertMethodAbsent(artifacts.applicationClasspath(), baselineMethod);
        OutputBytes legacyBytes = outputBytes();
        assertFalse(Arrays.equals(baselineBytes.api(), legacyBytes.api()));
        assertArrayEquals(baselineBytes.valueClass(), legacyBytes.valueClass());

        BuildResult legacyWarm = build(service, true);
        assertTrue(legacyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(baselineMethod, assertMangling(artifacts.applicationClasspath()));
        assertMethodAbsent(artifacts.applicationClasspath(), legacyMethod);
        OutputBytes restoredBytes = outputBytes();
        assertArrayEquals(baselineBytes.api(), restoredBytes.api());
        assertArrayEquals(baselineBytes.valueClass(), restoredBytes.valueClass());
    }

    private BuildResult build(BuildService service, boolean legacyMangling) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(legacyMangling),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Mangling.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @JvmInline
                value class UserId(val value: String)

                object ManglingApi {
                    @JvmStatic
                    fun render(prefix: String, id: UserId): String = "$prefix:${id.value}"
                }
                """);
    }

    private String assertMangling(List<Path> applicationClasspath) throws Exception {
        try (URLClassLoader loader = loader(applicationClasspath)) {
            Class<?> type = Class.forName("com.example.ManglingApi", true, loader);
            List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("render-"))
                    .toList();
            assertEquals(1, methods.size(), methods.toString());
            Method method = methods.getFirst();
            assertEquals("user:42", method.invoke(null, "user", "42"));
            return method.getName();
        }
    }

    private void assertMethodAbsent(
            List<Path> applicationClasspath,
            String methodName) throws Exception {
        try (URLClassLoader loader = loader(applicationClasspath)) {
            Class<?> type = Class.forName("com.example.ManglingApi", true, loader);
            assertThrows(
                    NoSuchMethodException.class,
                    () -> type.getDeclaredMethod(methodName, String.class, String.class));
        }
    }

    private URLClassLoader loader(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        return new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("ManglingApi.class")),
                Files.readAllBytes(output.resolve("UserId.class")));
    }

    private static ProjectConfig config(boolean legacyMangling) {
        String compilerArguments = legacyMangling
                ? "\"-parameters\", \"-Xuse-14-inline-classes-mangling-scheme\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-legacy-inline-class-mangling"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArguments));
    }

    private record OutputBytes(byte[] api, byte[] valueClass) {}
}
