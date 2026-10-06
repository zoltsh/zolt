package sh.zolt.cli.build.kotlin.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real offline CLI proof for Zolt's closed Kotlin Spring all-open preset. */
final class KotlinSpringAllOpenPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void opensSpringAnnotatedKotlinTypesOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishSpringAllOpen(repository);
            writeProject(project, repository);

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

            CommandResult build = execute(
                    "build",
                    "--offline",
                    "--no-build-cache",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");
            assertEquals(0, build.exitCode(), combined(build));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/classes/com/example/KotlinService.class")));

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("false:false:spring"), run.stdout());
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path component = project.resolve(
                "src/main/kotlin/org/springframework/stereotype/Component.kt");
        Path application = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(component.getParent());
        Files.createDirectories(application.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-spring-all-open"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.MainKt"

                [toolchain.kotlin]
                version = "%s"
                plugins = ["spring"]

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
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
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

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
