package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof that the locked serialization compiler plugin generates executable serializers. */
final class KotlinSerializationPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildsAndRunsGeneratedSerializerFromAnOfflineCache() throws Exception {
        Path project = tempDir.resolve("project");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishSerialization(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();
            repository.close();

            CommandResult build = execute(
                    "build",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, build.exitCode(), combined(build));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/classes/com/example/Message$$serializer.class")));

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("com.example.Message:real"), run.stdout());

            CommandResult packaging = execute(
                    "package",
                    "--mode", "uber-jar",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, packaging.exitCode(), combined(packaging));
            Path jarPath = project.resolve("target/kotlin-serialization-plugin-0.1.0.jar");
            assertPackagedInventory(jarPath);

            CommandResult packagedRun = execute(
                    "run-package",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, packagedRun.exitCode(), combined(packagedRun));
            assertTrue(
                    packagedRun.stdout().contains("com.example.Message:real"),
                    packagedRun.stdout());
            assertEquals(Map.of(), repository.authorizations());
        }
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

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path source = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-serialization-plugin"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.MainKt"

                [toolchain.kotlin]
                version = "%s"
                plugins = ["serialization"]

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
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.SERIALIZATION_RUNTIME_VERSION));
        Files.writeString(source, """
                package com.example

                import kotlinx.serialization.Serializable

                @Serializable
                data class Message(val value: String)

                fun main() {
                    val message = Message("real")
                    println(Message.serializer().descriptor.serialName + ":" + message.value)
                }
                """);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
