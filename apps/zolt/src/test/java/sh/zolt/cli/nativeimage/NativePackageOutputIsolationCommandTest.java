package sh.zolt.cli.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.sha256;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native's JVM input is staging state, never the configured package output. */
final class NativePackageOutputIsolationCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void nativePreservesConfiguredOutputsAndPublishEvidenceAcrossPackageModes() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addSpringArtifacts(repository);
            Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                    tempDir.resolve("fake-native-image"));
            for (String mode : List.of("thin", "uber", "spring-boot", "spring-boot-war")) {
                Path project = writeProject(mode, repository.baseUri().toString());
                Path cache = tempDir.resolve("cache-" + mode);
                assertSuccess(execute(
                        "resolve",
                        "--cwd", project.toString(),
                        "--cache-root", cache.toString()));
                assertSuccess(execute(
                        "package",
                        "--cwd", project.toString(),
                        "--cache-root", cache.toString()));
                Map<String, String> configuredOutputs = configuredOutputHashes(project);
                assertFalse(configuredOutputs.isEmpty());

                assertSuccess(nativeCommand(project, cache, nativeImage));
                assertEquals(configuredOutputs, configuredOutputHashes(project), mode);
                assertSuccess(nativeCommand(project, cache, nativeImage));
                assertEquals(configuredOutputs, configuredOutputHashes(project), mode);

                Path nativeInput = project.resolve("target/native/input/demo-0.1.0.jar");
                assertTrue(Files.isRegularFile(nativeInput), mode);
                assertPrivateInputEvidence(nativeInput, mode);
                assertPublishReady(project, cache);
            }
        }
    }

    @Test
    void nativeRejectsReservedAndConfiguredOutputCollisionsBeforeReplacingAnything() throws IOException {
        Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                tempDir.resolve("collision-native-image"));
        for (Collision collision : List.of(
                new Collision("uber", "target/native", "native-image.log"),
                new Collision("uber", "target/native", "spring-aot-evidence.json"),
                new Collision("uber", "target/native", "input"),
                new Collision("uber", "target", "demo-0.1.0.jar"),
                new Collision("war", "target", "demo-0.1.0.war"),
                new Collision("uber", "target", "demo-0.1.0.jar.zolt-package.json"))) {
            Path project = writeCollisionProject(collision);
            Path cache = tempDir.resolve("collision-cache-" + collision.id());
            assertSuccess(execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString()));
            assertSuccess(execute(
                    "package",
                    "--cwd", project.toString(),
                    "--cache-root", cache.toString()));
            Map<String, String> before = configuredOutputHashes(project);

            CommandResult result = nativeCommand(project, cache, nativeImage);

            assertEquals(1, result.exitCode(), collision.toString());
            assertTrue(result.stderr().contains("Native output ownership conflict"), result.stderr());
            assertEquals(before, configuredOutputHashes(project), collision.toString());
        }
    }

    private Path writeProject(String mode, String repositoryUrl) throws IOException {
        Path project = tempDir.resolve("project-" + mode);
        Files.createDirectories(project);
        String spring = mode.startsWith("spring-boot")
                ? """

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [framework.springBoot.native]
                enabled = true
                """
                : "";
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                test = "%s"

                [package]
                mode = "%s"
                sources = true
                %s
                [publish]
                releaseRepository = "test-releases"

                [publish.repositories.test-releases]
                url = "https://repo.example.test/releases"
                """.formatted(Runtime.version().feature(), repositoryUrl, mode, spring));
        Path source = project.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        return project;
    }

    private Path writeCollisionProject(Collision collision) throws IOException {
        Path project = tempDir.resolve("collision-" + collision.id());
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [package]
                mode = "%s"
                sources = true

                [native]
                output = "%s"
                imageName = "%s"
                """.formatted(
                Runtime.version().feature(),
                collision.mode(),
                collision.output(),
                collision.imageName()));
        Path source = project.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        return project;
    }

    private void addSpringArtifacts(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "org.springframework.boot", "spring-boot-dependencies", "3.3.6", platformPom());
        repository.addArtifact(
                "org.springframework.boot", "spring-boot-loader", "3.3.6",
                pom("spring-boot-loader"), fakeSpringBootLoaderJar());
        repository.addArtifact(
                "org.springframework.boot", "spring-boot", "3.3.6",
                pom("spring-boot"), NativeCommandTestSupport.fakeSpringBootAotJar(
                        tempDir.resolve("native-isolation-aot")));
    }

    private static Map<String, String> configuredOutputHashes(Path project) throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (var paths = Files.list(project.resolve("target"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().startsWith("demo-0.1.0"))
                    .sorted()
                    .toList()) {
                hashes.put(path.getFileName().toString(), sha256(path));
            }
        }
        return hashes;
    }

    private static CommandResult nativeCommand(Path project, Path cache, Path nativeImage) {
        return execute(
                "native",
                "--native-image", nativeImage.toString(),
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertPrivateInputEvidence(Path nativeInput, String configuredMode) throws IOException {
        Path evidence = nativeInput.resolveSibling("demo-0.1.0.jar.zolt-package.json");
        assertTrue(Files.isRegularFile(evidence), configuredMode);
        if ("uber".equals(configuredMode)) {
            return;
        }
        Path runtimeClasspath = nativeInput.resolveSibling("demo-0.1.0.runtime-classpath");
        assertTrue(Files.isRegularFile(runtimeClasspath), configuredMode);
        if (configuredMode.startsWith("spring-boot")) {
            assertTrue(Files.readString(runtimeClasspath).contains("spring-boot-loader"), configuredMode);
            String manifest = Files.readString(evidence);
            assertTrue(manifest.contains("\"mode\": \"thin\""), manifest);
            assertTrue(manifest.contains("\"kind\": \"runtime-classpath\""), manifest);
        }
    }

    private static void assertPublishReady(Path project, Path cache) {
        CommandResult published = execute(
                "publish", "--dry-run",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
        assertSuccess(published);
        assertTrue(published.stdout().contains("Status: ready"), published.stdout());
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
    }

    private static String pom(String artifact) {
        return """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>%s</artifactId>
                  <version>3.3.6</version>
                </project>
                """.formatted(artifact);
    }

    private static byte[] fakeSpringBootLoaderJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (String launcher : List.of("JarLauncher", "WarLauncher")) {
                output.putNextEntry(new JarEntry(
                        "org/springframework/boot/loader/launch/" + launcher + ".class"));
                output.write(0);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String platformPom() {
        return """
                <project>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-dependencies</artifactId>
                  <version>3.3.6</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-loader</artifactId>
                        <version>3.3.6</version>
                      </dependency>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot</artifactId>
                        <version>3.3.6</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
    }

    private record Collision(String mode, String output, String imageName) {
        String id() {
            return mode + "-" + output.replace('/', '-') + "-" + imageName.replace('.', '-');
        }
    }
}
