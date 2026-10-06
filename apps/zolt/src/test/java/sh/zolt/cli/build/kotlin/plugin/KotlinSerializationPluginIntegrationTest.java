package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;

/** Real CLI proof that locked serialization compiler-plugin outputs are executable and cache-safe. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinSerializationPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildsCachesInvalidatesAndPackagesGeneratedSerializerOffline() throws Exception {
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
            KotlinCompilerCliFixture.publishSerialization(repository);
            writeProject(project, repository.baseUri().toString());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult cold = build(project, artifactCache);
            assertEquals(0, cold.exitCode(), combined(cold));
            assertTiming(cold, "full");
            Path serializer = project.resolve(
                    "target/classes/com/example/Message$$serializer.class");
            assertTrue(Files.isRegularFile(serializer));
            byte[] serializerBytes = Files.readAllBytes(serializer);

            CommandResult warm = build(project, artifactCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertTiming(warm, "skipped");

            KotlinCliBuildCacheTestSupport.deleteTrees(project.resolve("target"));
            CommandResult restored = build(project, artifactCache);
            assertEquals(0, restored.exitCode(), combined(restored));
            assertTiming(restored, "restored");
            assertArrayEquals(serializerBytes, Files.readAllBytes(serializer));

            writeManifest(project, repository.baseUri().toString(), false);
            resolveOffline(project, artifactCache);
            CommandResult withoutPlugin = build(project, artifactCache);
            assertEquals(0, withoutPlugin.exitCode(), combined(withoutPlugin));
            assertTiming(withoutPlugin, "full");
            assertFalse(Files.exists(serializer), "plugin removal must not reuse generated output");

            writeManifest(project, repository.baseUri().toString(), true);
            resolveOffline(project, artifactCache);
            CommandResult pluginReenabled = build(project, artifactCache);
            assertEquals(0, pluginReenabled.exitCode(), combined(pluginReenabled));
            assertTiming(pluginReenabled, "full");
            assertTrue(Files.isRegularFile(serializer));

            assertRun(project, artifactCache);
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
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
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

    private static void assertRun(Path project, Path artifactCache) {
        CommandResult run = execute(
                "run",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
        assertEquals(0, run.exitCode(), combined(run));
        assertTrue(run.stdout().contains("com.example.Message:real"), run.stdout());
    }

    private static void assertPackage(Path project, Path artifactCache) throws IOException {
        CommandResult packaging = execute(
                "package",
                "--mode", "uber-jar",
                "--no-build-cache",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
        assertEquals(0, packaging.exitCode(), combined(packaging));
        Path jarPath = project.resolve("target/kotlin-serialization-plugin-0.1.0.jar");
        assertPackagedInventory(jarPath);

        CommandResult packagedRun = execute(
                "run-package",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString());
        assertEquals(0, packagedRun.exitCode(), combined(packagedRun));
        assertTrue(
                packagedRun.stdout().contains("com.example.Message:real"),
                packagedRun.stdout());
    }

    private static void assertPackagedInventory(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Message$$serializer.class"));
            assertNotNull(jar.getEntry("kotlinx/serialization/KSerializer.class"));
            assertNull(jar.getEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"));
            assertNull(jar.getEntry(
                    "org/jetbrains/kotlinx/serialization/compiler/extensions/"
                            + "SerializationComponentRegistrar.class"));
        }
    }

    private static void writeProject(Path project, String repositoryUrl) throws IOException {
        Path source = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        writeManifest(project, repositoryUrl, true);
        Files.writeString(source, """
                package com.example

                import kotlinx.serialization.Serializable

                @Serializable
                data class Message(val value: String)

                fun main() {
                    val message = Message("real")
                    val serializer = Class.forName("com.example.Message${'$'}${'$'}serializer")
                    val serializedType = serializer.name.removeSuffix("${'$'}${'$'}serializer")
                    println(serializedType + ":" + message.value)
                }
                """);
    }

    private static void writeManifest(
            Path project,
            String repositoryUrl,
            boolean serializationPlugin) throws IOException {
        String plugins = serializationPlugin ? "plugins = [\"serialization\"]\n" : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-serialization-plugin"
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
                "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                plugins,
                repositoryUrl,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.SERIALIZATION_RUNTIME_VERSION));
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
