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

/** Real CLI proof that the closed Spring and JPA compiler-plugin selectors compose. */
final class KotlinSpringJpaPluginIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void opensAndAddsANoArgConstructorToTheSameTypeOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path artifactCache = tempDir.resolve("artifact-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.publishSpringAllOpen(repository);
            KotlinCompilerCliFixture.publishJpaNoArg(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
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
                    "target/classes/com/example/KotlinEntity.class")));

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", artifactCache.toString(),
                    "--no-progress");
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("false:false:true:true:combined"), run.stdout());
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static void writeProject(
            Path project,
            CliTestRepository repository) throws IOException {
        Path component = project.resolve(
                "src/main/kotlin/org/springframework/stereotype/Component.kt");
        Path entity = project.resolve("src/main/kotlin/jakarta/persistence/Entity.kt");
        Path application = project.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(component.getParent());
        Files.createDirectories(entity.getParent());
        Files.createDirectories(application.getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "kotlin-spring-jpa"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.MainKt"

                [toolchain.kotlin]
                version = "%s"
                plugins = ["spring", "jpa"]

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
        Files.writeString(entity, """
                package jakarta.persistence

                @Target(AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Entity
                """);
        Files.writeString(application, """
                package com.example

                import jakarta.persistence.Entity
                import java.lang.reflect.Modifier
                import org.springframework.stereotype.Component

                @Component
                @Entity
                class KotlinEntity(val name: String) {
                    fun message(): String = "combined"
                }

                fun main() {
                    val type = KotlinEntity::class.java
                    val method = type.getDeclaredMethod("message")
                    val constructor = type.getDeclaredConstructor()
                    val instance = constructor.newInstance()
                    println(
                        Modifier.isFinal(type.modifiers).toString()
                            + ":" + Modifier.isFinal(method.modifiers)
                            + ":" + (constructor.parameterCount == 0)
                            + ":" + type.isInstance(instance)
                            + ":" + instance.message(),
                    )
                }
                """);
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}
