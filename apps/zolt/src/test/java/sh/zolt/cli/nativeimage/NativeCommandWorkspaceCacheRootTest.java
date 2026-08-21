package sh.zolt.cli.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCommandWorkspaceCacheRootTest {
    @TempDir
    private Path tempDir;

    @Test
    void workspaceNativePackageEvidenceIgnoresUnrelatedDefaultCacheContents() throws IOException {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact(
                    "com.example",
                    "source-generator",
                    "1.0.0",
                    pom(),
                    NativeGeneratedSourceTestFixture.jar(tempDir.resolve("generator-work")));
            Path workspace = writeWorkspace(repository.baseUri().toString());
            Path cache = tempDir.resolve("custom-cache");
            Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                    tempDir.resolve("cache-root-native-image"));

            assertSuccess(execute(
                    "resolve", "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString()));
            String stableLock = Files.readString(workspace.resolve("zolt.lock"));
            assertSuccess(nativeCommand(workspace, cache, nativeImage));

            Path evidence = workspace.resolve(
                    "apps/app/target/native/input/app-0.1.0.jar.zolt-package.json");
            String firstFingerprint = jsonField(Files.readString(evidence), "buildInputFingerprint");

            Path customToolJar = toolJar(cache);
            Path defaultToolJar = fakeUserHome
                    .resolve(".zolt/cache")
                    .resolve(cache.relativize(customToolJar));
            Files.createDirectories(defaultToolJar.getParent());
            Files.copy(customToolJar, defaultToolJar, StandardCopyOption.REPLACE_EXISTING);

            assertSuccess(nativeCommand(workspace, cache, nativeImage));

            assertEquals(stableLock, Files.readString(workspace.resolve("zolt.lock")));
            assertEquals(
                    firstFingerprint,
                    jsonField(Files.readString(evidence), "buildInputFingerprint"));
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private Path writeWorkspace(String repositoryUrl) throws IOException {
        Path workspace = tempDir.resolve("workspace-cache-root");
        Path member = workspace.resolve("apps/app");
        Files.createDirectories(member);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-cache-root"

                [workspace.members]
                default = ["apps/app"]
                include = ["apps/app"]
                """);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "app"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [generated.tools.source-generator]
                kind = "jvm"
                coordinates = [{ coordinate = "com.example:source-generator", version = "1.0.0" }]
                mainClass = "com.example.tool.SourceGenerator"

                [generated.main.model]
                kind = "exec"
                tool = "source-generator"
                inputs = ["model.txt"]
                output = "target/generated/sources/model"
                produces = "java-sources"
                """.formatted(repositoryUrl));
        Files.writeString(member.resolve("model.txt"), "model-v1\n");
        Path source = member.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        Path generated = member.resolve("target/generated/sources/model/com/example/generated/Generated.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, """
                package com.example.generated;

                public final class Generated {
                }
                """);
        return workspace;
    }

    private static CommandResult nativeCommand(Path workspace, Path cache, Path nativeImage) {
        return execute(
                "native", "--workspace", "--member", "apps/app",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString(),
                "--native-image", nativeImage.toString());
    }

    private static Path toolJar(Path cache) throws IOException {
        try (var paths = Files.walk(cache)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static String jsonField(String json, String field) {
        String prefix = "\"" + field + "\": \"";
        return json.lines()
                .map(String::trim)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length(), line.length() - 2))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSuccess(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
    }

    private static String pom() {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>source-generator</artifactId>
                  <version>1.0.0</version>
                </project>
                """;
    }
}
