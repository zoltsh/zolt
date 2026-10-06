package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof that the locked JPA no-arg preset emits its bounded bytecode contract. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinJpaNoArgPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void cachesAndInvalidatesGeneratedJpaConstructorOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            String repositoryUrl = repository.baseUri().toString();
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishJpaNoArg(repository);
            writeProject(project, repositoryUrl);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            assertTrue(Files.readString(project.resolve("zolt.lock")).contains(
                    "id = \"org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable\""));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult cold = build(project, artifactCache);
            assertEquals(0, cold.exitCode(), combined(cold));
            assertTiming(cold, "full");
            Path entityClass = project.resolve("target/classes/com/example/JpaEntity.class");
            assertTrue(Files.isRegularFile(entityClass));
            byte[] noArgBytes = Files.readAllBytes(entityClass);
            assertRun(project, artifactCache);

            CommandResult warm = build(project, artifactCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertTiming(warm, "skipped");

            KotlinCliBuildCacheTestSupport.deleteTrees(project.resolve("target"));
            CommandResult restored = build(project, artifactCache);
            assertEquals(0, restored.exitCode(), combined(restored));
            assertTiming(restored, "restored");
            assertArrayEquals(noArgBytes, Files.readAllBytes(entityClass));
            assertRun(project, artifactCache);

            writeManifest(project, repositoryUrl, false);
            resolveOffline(project, artifactCache);
            CommandResult withoutPlugin = build(project, artifactCache);
            assertEquals(0, withoutPlugin.exitCode(), combined(withoutPlugin));
            assertTiming(withoutPlugin, "full");
            CommandResult failedRun = run(project, artifactCache);
            assertNotEquals(0, failedRun.exitCode(), combined(failedRun));
            assertTrue(combined(failedRun).contains("NoSuchMethodException"), combined(failedRun));

            writeManifest(project, repositoryUrl, true);
            resolveOffline(project, artifactCache);
            CommandResult reenabled = build(project, artifactCache);
            assertEquals(0, reenabled.exitCode(), combined(reenabled));
            assertTiming(reenabled, "full");
            assertArrayEquals(noArgBytes, Files.readAllBytes(entityClass));
            assertRun(project, artifactCache);
            assertEquals(Map.of(), repository.authorizations());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void writeProject(
            Path project,
            String repositoryUrl) throws IOException {
        Path annotation = project.resolve(
                "src/main/kotlin/jakarta/persistence/Entity.kt");
        Path application = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(annotation.getParent());
        Files.createDirectories(application.getParent());
        writeManifest(project, repositoryUrl, true);
        Files.writeString(annotation, """
                package jakarta.persistence

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Entity
                """);
        Files.writeString(application, """
                package com.example

                import jakarta.persistence.Entity
                import java.lang.reflect.Modifier

                @Entity
                class JpaEntity(val name: String)

                fun main() {
                    val type = JpaEntity::class.java
                    val constructor = type.getDeclaredConstructor()
                    val entity = constructor.newInstance()
                    println(
                        Modifier.isFinal(type.modifiers).toString()
                            + ":" + (constructor.parameterCount == 0)
                            + ":" + type.isInstance(entity),
                    )
                }
                """);
    }

    private static void writeManifest(
            Path project,
            String repositoryUrl,
            boolean jpaPlugin) throws IOException {
        String plugins = jpaPlugin ? "plugins = [\"jpa\"]\n" : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-jpa-no-arg"
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

    private static void assertRun(Path project, Path artifactCache) {
        CommandResult result = run(project, artifactCache);
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("true:true:true"), result.stdout());
    }

    private static CommandResult run(Path project, Path artifactCache) {
        return execute(
                "run",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
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
