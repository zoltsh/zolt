package sh.zolt.cli.build;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import sh.zolt.cli.CliTestRepository;

/** Publishes the real test-runtime Kotlin compiler closure through a hermetic Maven repository. */
final class KotlinCompilerCliFixture {
    static final String KOTLIN_VERSION = "2.2.0";

    private static final Artifact COMPILER = artifact(
            "org.jetbrains.kotlin",
            "kotlin-compiler-embeddable",
            KOTLIN_VERSION,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
    private static final Artifact DAEMON = artifact(
            "org.jetbrains.kotlin",
            "kotlin-daemon-embeddable",
            KOTLIN_VERSION,
            "org.jetbrains.kotlin.daemon.common.CompileService");
    private static final Artifact REFLECT = artifact(
            "org.jetbrains.kotlin",
            "kotlin-reflect",
            "1.6.10",
            "kotlin.reflect.jvm.internal.ReflectionFactoryImpl");
    private static final Artifact SCRIPT_RUNTIME = artifact(
            "org.jetbrains.kotlin",
            "kotlin-script-runtime",
            KOTLIN_VERSION,
            "kotlin.script.templates.standard.ScriptTemplateWithArgs");
    private static final Artifact STDLIB = artifact(
            "org.jetbrains.kotlin",
            "kotlin-stdlib",
            KOTLIN_VERSION,
            "kotlin.Unit");
    private static final Artifact COROUTINES = artifact(
            "org.jetbrains.kotlinx",
            "kotlinx-coroutines-core-jvm",
            "1.8.0",
            "kotlinx.coroutines.Job");
    private static final Artifact ANNOTATIONS = artifact(
            "org.jetbrains",
            "annotations",
            "13.0",
            "org.jetbrains.annotations.NotNull");

    private KotlinCompilerCliFixture() {
    }

    static void publish(CliTestRepository repository) throws IOException {
        publish(repository, COMPILER, List.of(DAEMON, REFLECT, SCRIPT_RUNTIME, STDLIB, COROUTINES));
        publish(repository, DAEMON, List.of());
        publish(repository, REFLECT, List.of());
        publish(repository, SCRIPT_RUNTIME, List.of());
        publish(repository, STDLIB, List.of(ANNOTATIONS));
        publish(repository, COROUTINES, List.of());
        publish(repository, ANNOTATIONS, List.of());
    }

    static void writeProject(Path projectDirectory, URI repository) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/kotlin/com/example"));
        Files.writeString(projectDirectory.resolve("zolt.toml"), """
                [project]
                name = "kotlin-cli"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [toolchain.kotlin]
                version = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KOTLIN_VERSION,
                repository,
                KOTLIN_VERSION));
        writeSource(projectDirectory, "first");
    }

    static void writeSource(Path projectDirectory, String value) throws IOException {
        Files.writeString(projectDirectory.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(listOf("real", "kotlin", "%s").joinToString("-"))
                    }
                }
                """.formatted(value));
    }

    static Path compilerJar(Path cacheRoot) throws IOException {
        try (var paths = Files.walk(cacheRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .equals("kotlin-compiler-embeddable-" + KOTLIN_VERSION + ".jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin compiler was not cached under " + cacheRoot));
        }
    }

    private static void publish(
            CliTestRepository repository,
            Artifact artifact,
            List<Artifact> dependencies) throws IOException {
        repository.addArtifact(
                artifact.groupId(),
                artifact.artifactId(),
                artifact.version(),
                pom(artifact, dependencies),
                Files.readAllBytes(markerJar(artifact.markerClass())));
    }

    private static String pom(Artifact artifact, List<Artifact> dependencies) {
        String dependencyXml = dependencies.stream()
                .map(dependency -> """
                        <dependency>
                          <groupId>%s</groupId>
                          <artifactId>%s</artifactId>
                          <version>%s</version>
                        </dependency>
                        """.formatted(
                        dependency.groupId(),
                        dependency.artifactId(),
                        dependency.version()))
                .reduce("", String::concat);
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(
                artifact.groupId(),
                artifact.artifactId(),
                artifact.version(),
                dependencyXml);
    }

    private static Path markerJar(String markerClass) {
        try {
            Class<?> marker = Class.forName(
                    markerClass,
                    false,
                    KotlinCompilerCliFixture.class.getClassLoader());
            Path location = Path.of(marker.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException(
                        "Kotlin integration marker " + markerClass + " is not loaded from a JAR: " + location);
            }
            return location;
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException(
                    "Kotlin integration marker is unavailable on the test runtime: " + markerClass,
                    exception);
        }
    }

    private static Artifact artifact(
            String groupId,
            String artifactId,
            String version,
            String markerClass) {
        return new Artifact(groupId, artifactId, version, markerClass);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }

    private record Artifact(
            String groupId,
            String artifactId,
            String version,
            String markerClass) {
    }
}
