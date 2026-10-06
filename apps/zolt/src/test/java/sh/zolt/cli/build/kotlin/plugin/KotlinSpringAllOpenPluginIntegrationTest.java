package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof that Spring all-open bytecode is executable and cache-safe. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinSpringAllOpenPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void cachesAndInvalidatesSpringAllOpenBytecodeOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishAllOpen(repository);
            writeProject(project, repository.baseUri().toString());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            assertTrue(Files.readString(project.resolve("zolt.lock")).contains(
                    "id = \"org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable\""));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult cold = build(project, artifactCache);
            assertEquals(0, cold.exitCode(), combined(cold));
            assertTiming(cold, "full");
            Path service = project.resolve("target/classes/com/example/KotlinService.class");
            assertTrue(Files.isRegularFile(service));
            byte[] openBytes = Files.readAllBytes(service);
            assertRun(project, artifactCache, "false:false:spring");

            CommandResult warm = build(project, artifactCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertTiming(warm, "skipped");

            KotlinCliBuildCacheTestSupport.deleteTrees(project.resolve("target"));
            CommandResult restored = build(project, artifactCache);
            assertEquals(0, restored.exitCode(), combined(restored));
            assertTiming(restored, "restored");
            assertArrayEquals(openBytes, Files.readAllBytes(service));
            assertRun(project, artifactCache, "false:false:spring");

            writeManifest(project, repository.baseUri().toString(), false);
            resolveOffline(project, artifactCache);
            CommandResult withoutPlugin = build(project, artifactCache);
            assertEquals(0, withoutPlugin.exitCode(), combined(withoutPlugin));
            assertTiming(withoutPlugin, "full");
            assertRun(project, artifactCache, "true:true:spring");

            writeManifest(project, repository.baseUri().toString(), true);
            resolveOffline(project, artifactCache);
            CommandResult reenabled = build(project, artifactCache);
            assertEquals(0, reenabled.exitCode(), combined(reenabled));
            assertTiming(reenabled, "full");
            assertRun(project, artifactCache, "false:false:spring");
            assertPackage(project, artifactCache);
            assertEquals(Map.of(), repository.authorizations());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult build(Path project, Path artifactCache) {
        return execute(
                "build",
                "--offline",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
    }

    private static void resolveOffline(Path project, Path artifactCache) {
        CommandResult resolve = execute(
                "resolve",
                "--offline",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
        assertEquals(0, resolve.exitCode(), combined(resolve));
    }

    private static void assertRun(
            Path project,
            Path artifactCache,
            String expected) {
        CommandResult run = execute(
                "run",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
        assertEquals(0, run.exitCode(), combined(run));
        assertTrue(run.stdout().contains(expected), run.stdout());
    }

    private static void assertPackage(Path project, Path artifactCache) throws IOException {
        CommandResult packaging = execute(
                "package",
                "--mode", "uber-jar",
                "--no-build-cache",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
        assertEquals(0, packaging.exitCode(), combined(packaging));
        Path jarPath = project.resolve("target/kotlin-spring-all-open-0.1.0.jar");
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/KotlinService.class"));
            assertNotNull(jar.getEntry("kotlin/jvm/internal/Intrinsics.class"));
            assertNull(jar.getEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"));
            assertNull(jar.getEntry(
                    "org/jetbrains/kotlin/allopen/AllOpenCommandLineProcessor.class"));
        }

        CommandResult packagedRun = execute(
                "run-package",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
        assertEquals(0, packagedRun.exitCode(), combined(packagedRun));
        assertTrue(packagedRun.stdout().contains("false:false:spring"), packagedRun.stdout());
    }

    private static void writeProject(Path project, String repositoryUrl) throws IOException {
        Path component = project.resolve(
                "src/main/kotlin/org/springframework/stereotype/Component.kt");
        Path application = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(component.getParent());
        Files.createDirectories(application.getParent());
        writeManifest(project, repositoryUrl, true);
        Files.writeString(component, """
                package org.springframework.stereotype

                @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Component
                """);
        Files.writeString(application, """
                package com.example

                import java.lang.reflect.Modifier
                import org.springframework.stereotype.Component

                @Component
                class KotlinService {
                    fun message(): String = "spring"
                }

                fun main() {
                    val type = KotlinService::class.java
                    val method = type.getDeclaredMethod("message")
                    println(
                        Modifier.isFinal(type.modifiers).toString()
                            + ":" + Modifier.isFinal(method.modifiers)
                            + ":" + KotlinService().message(),
                    )
                }
                """);
    }

    private static void writeManifest(
            Path project,
            String repositoryUrl,
            boolean springPlugin) throws IOException {
        String plugins = springPlugin ? "plugins = [\"spring\"]\n" : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-spring-all-open"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.MainKt"

                [toolchain.kotlin]
                version = "%s"
                %s

                [build]
                sources = ["src/main/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                plugins,
                repositoryUrl,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile main timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
