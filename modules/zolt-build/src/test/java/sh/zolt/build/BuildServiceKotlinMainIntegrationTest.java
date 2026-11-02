package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** End-to-end proof that the production build path runs an isolated real Kotlin compiler. */
final class BuildServiceKotlinMainIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void compilesOfflineWithAnIsolatedToolchainAndSafelyReusesOutput() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        String javaSourceContent = javaApiSource("\"real\"");
        Path javaSource = source("src/main/java/com/example/JavaApi.java", javaSourceContent);
        Path kotlinSource = source("src/main/kotlin/com/example/KotlinApi.kt", """
                package com.example

                object KotlinApi {
                    @JvmStatic
                    fun message(): String = JavaApi.javaValue() + "-" + kotlinValue()

                    @JvmStatic
                    fun kotlinValue(): String = listOf("kotlin").joinToString()
                }
                """);
        Path obsoleteSource = source("src/main/kotlin/com/example/Obsolete.kt", """
                package com.example

                class Obsolete
                """);
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "kotlin-main-integration"));

        BuildResultWithClasspaths first = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);

        assertTrue(first.buildResult().resolveResult().isEmpty());
        assertEquals(3, first.buildResult().sourceCount());
        assertEquals("full", first.buildResult().mainCompilationMode());
        assertEquals("kotlin-main-sources", first.buildResult().mainIncrementalFallbackReason());
        assertEquals("stored", first.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
        assertTrue(Files.isRegularFile(classFile("Obsolete.class")));
        assertTrue(hasKotlinModuleMetadata());
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
        assertEquals("kotlin", invokeJavaApi(artifacts.applicationClasspath()));
        assertIsolatedCompilerClasspath(first, artifacts);

        BuildResultWithClasspaths warm = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(warm.buildResult().resolveResult().isEmpty());
        assertTrue(warm.buildResult().mainCompilationSkipped());
        assertEquals(3, warm.buildResult().sourceCount());

        wipeTarget();
        BuildResultWithClasspaths restored = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(restored.buildResult().mainCompilationRestored());
        assertEquals("restored", restored.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
        assertTrue(Files.isRegularFile(classFile("Obsolete.class")));
        assertTrue(hasKotlinModuleMetadata());
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        Files.writeString(javaSource, javaApiSource("\"changed\""));
        BuildResultWithClasspaths javaChanged = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertEquals("full", javaChanged.buildResult().mainCompilationMode());
        assertEquals(3, javaChanged.buildResult().sourceCount());
        assertEquals("changed-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        Files.writeString(javaSource, javaApiSource("Missing.symbol()"));
        JavacException javacFailure = assertThrows(
                JavacException.class,
                () -> service.buildWithClasspaths(projectDir, config(), cacheRoot, true));
        assertTrue(javacFailure.getMessage().contains("javac failed"), javacFailure.getMessage());

        Files.writeString(javaSource, javaApiSource("\"recovered\""));
        BuildResultWithClasspaths recovered = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertFalse(recovered.buildResult().mainCompilationRestored());
        assertEquals("full", recovered.buildResult().mainCompilationMode());
        assertEquals("recovered-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        Files.writeString(javaSource, javaSourceContent);
        BuildResultWithClasspaths reverted = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertFalse(reverted.buildResult().mainCompilationSkipped());
        assertEquals(3, reverted.buildResult().sourceCount());
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
        assertEquals("kotlin", invokeJavaApi(artifacts.applicationClasspath()));

        Files.delete(obsoleteSource);
        BuildResultWithClasspaths rebuilt = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertFalse(rebuilt.buildResult().mainCompilationSkipped());
        assertFalse(rebuilt.buildResult().mainCompilationRestored());
        assertEquals("full", rebuilt.buildResult().mainCompilationMode());
        assertEquals("kotlin-main-sources", rebuilt.buildResult().mainIncrementalFallbackReason());
        assertEquals(2, rebuilt.buildResult().sourceCount());
        assertFalse(Files.exists(classFile("Obsolete.class")));
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
        assertTrue(Files.isRegularFile(kotlinSource));
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
        assertEquals("kotlin", invokeJavaApi(artifacts.applicationClasspath()));
    }

    @Test
    void compilesDeclaredJavaRootAndInvalidatesWhenItsContentChanges() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        source("src/main/kotlin/com/example/KotlinApi.kt", """
                package com.example

                object KotlinApi {
                    @JvmStatic
                    fun message(): String = DeclaredJava.javaValue() + "-" + kotlinValue()

                    @JvmStatic
                    fun kotlinValue(): String = "kotlin"
                }
                """);
        source("schema/api.txt", "declared-root-input\n");
        Path declaredJava = source(
                "generated/main/com/example/DeclaredJava.java",
                declaredJavaSource("\"declared-v1\""));
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "kotlin-declared-root-integration"));

        BuildResultWithClasspaths first = service.buildWithClasspaths(
                projectDir,
                declaredRootConfig(),
                cacheRoot,
                true);

        assertEquals(2, first.buildResult().sourceCount());
        assertEquals("full", first.buildResult().mainCompilationMode());
        assertEquals("stored", first.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("DeclaredJava.class")));
        assertEquals("declared-v1-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
        assertEquals(
                "kotlin",
                invokeApi(artifacts.applicationClasspath(), "com.example.DeclaredJava", "callKotlin"));

        BuildResultWithClasspaths warm = service.buildWithClasspaths(
                projectDir,
                declaredRootConfig(),
                cacheRoot,
                true);
        assertTrue(warm.buildResult().mainCompilationSkipped());

        wipeTarget();
        assertTrue(Files.isRegularFile(declaredJava));
        BuildResultWithClasspaths restored = service.buildWithClasspaths(
                projectDir,
                declaredRootConfig(),
                cacheRoot,
                true);
        assertTrue(restored.buildResult().mainCompilationRestored());
        assertTrue(Files.isRegularFile(declaredJava));
        assertTrue(hasKotlinModuleMetadata());
        assertEquals("declared-v1-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        Files.writeString(declaredJava, declaredJavaSource("\"declared-v2\""));
        BuildResultWithClasspaths changed = service.buildWithClasspaths(
                projectDir,
                declaredRootConfig(),
                cacheRoot,
                true);

        assertFalse(changed.buildResult().mainCompilationSkipped());
        assertFalse(changed.buildResult().mainCompilationRestored());
        assertEquals("full", changed.buildResult().mainCompilationMode());
        assertTrue(Files.isRegularFile(declaredJava));
        assertEquals("declared-v2-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        deleteRecursively(projectDir.resolve("generated/main"));
        SourceDiscoveryException missingRoot = assertThrows(
                SourceDiscoveryException.class,
                () -> service.buildWithClasspaths(projectDir, declaredRootConfig(), cacheRoot, true));
        assertTrue(missingRoot.getMessage().contains("Generated source root `generated/main` is missing"));
        assertTrue(Files.isRegularFile(classFile("DeclaredJava.class")));
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
    }

    private void assertIsolatedCompilerClasspath(
            BuildResultWithClasspaths result,
            KotlinCompilerIntegrationArtifacts.Prepared artifacts) {
        List<Path> compileEntries = result.classpaths().compile().entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        assertEquals(new HashSet<>(artifacts.applicationClasspath()), new HashSet<>(compileEntries));
        assertEquals(2, compileEntries.size());
        assertEquals(7, result.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .count());
        Set<Path> applicationEntries = Set.copyOf(compileEntries);
        List<Path> toolOnlyEntries = result.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .map(ResolvedClasspathPackage::resolvedPackage)
                .map(resolved -> resolved.jarPath().toAbsolutePath().normalize())
                .filter(path -> !applicationEntries.contains(path))
                .toList();
        assertEquals(5, toolOnlyEntries.size());
        assertTrue(artifacts.compilerClasspath().containsAll(toolOnlyEntries));
        assertTrue(compileEntries.stream().noneMatch(toolOnlyEntries::contains));
    }

    private String invokeKotlinApi(List<Path> applicationClasspath) throws Exception {
        return (String) invokeApi(applicationClasspath, "com.example.KotlinApi", "message");
    }

    private String invokeJavaApi(List<Path> applicationClasspath) throws Exception {
        return (String) invokeApi(applicationClasspath, "com.example.JavaApi", "callKotlin");
    }

    private Object invokeApi(
            List<Path> applicationClasspath,
            String className,
            String methodName) throws Exception {
        URL[] urls = Stream.concat(
                        Stream.of(projectDir.resolve("target/classes")),
                        applicationClasspath.stream())
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException exception) {
                        throw new IllegalArgumentException(exception);
                    }
                })
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> api = Class.forName(className, true, loader);
            return api.getMethod(methodName).invoke(null);
        }
    }

    private static String javaApiSource(String javaValueExpression) {
        return """
                package com.example;

                public final class JavaApi {
                    private JavaApi() {}

                    public static String javaValue() {
                        return %s;
                    }

                    public static String callKotlin() {
                        return KotlinApi.kotlinValue();
                    }
                }
                """.formatted(javaValueExpression);
    }

    private static String declaredJavaSource(String javaValueExpression) {
        return """
                package com.example;

                public final class DeclaredJava {
                    private DeclaredJava() {}

                    public static String javaValue() {
                        return %s;
                    }

                    public static String callKotlin() {
                        return KotlinApi.kotlinValue();
                    }
                }
                """.formatted(javaValueExpression);
    }

    private boolean hasKotlinModuleMetadata() throws IOException {
        Path metadata = projectDir.resolve("target/classes/META-INF");
        if (!Files.isDirectory(metadata)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(metadata)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".kotlin_module"));
        }
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void wipeTarget() throws IOException {
        deleteRecursively(projectDir.resolve("target"));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """);
    }

    private static ProjectConfig declaredRootConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-declared-root"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [generated.main.prebuilt]
                kind = "declared-root"
                language = "java"
                output = "generated/main"
                inputs = ["schema/api.txt"]
                required = true
                clean = false

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """);
    }
}
