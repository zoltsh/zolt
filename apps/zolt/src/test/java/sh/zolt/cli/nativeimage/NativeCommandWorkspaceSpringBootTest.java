package sh.zolt.cli.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.sha256;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeCommandWorkspaceSpringBootTest {
    @TempDir
    private Path tempDir;

    @Test
    void workspaceSpringBootNativeUsesMemberAotToolsAndPreservesPackageOutputs() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addSpringArtifacts(repository);
            Path workspace = writeImplicitWorkspace(repository.baseUri().toString());
            Path cache = tempDir.resolve("workspace-spring-cache");
            Path nativeImage = NativeCommandTestSupport.writeFakeNativeImage(
                    tempDir.resolve("workspace-spring-native-image"));

            assertSuccess(execute(
                    "resolve", "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString()));
            String stableLock = Files.readString(workspace.resolve("zolt.lock"));
            assertTrue(stableLock.contains("scope = \"tool-spring-aot\""), stableLock);

            assertSuccess(nativeCommand(workspace, cache, nativeImage, false, "apps/implicit"));
            assertEquals(stableLock, Files.readString(workspace.resolve("zolt.lock")));
            assertImplicitLoaderExcluded(workspace.resolve("apps/implicit"));

            assertSuccess(execute(
                    "package", "--workspace", "--all",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString()));
            Map<Path, String> configuredOutputs = configuredOutputHashes(workspace);
            assertFalse(configuredOutputs.isEmpty());

            assertSuccess(nativeCommand(workspace, cache, nativeImage, true, ""));

            assertEquals(stableLock, Files.readString(workspace.resolve("zolt.lock")));
            assertEquals(configuredOutputs, configuredOutputHashes(workspace));
            assertImplicitLoaderExcluded(workspace.resolve("apps/implicit"));
            assertImplicitLoaderExcluded(workspace.resolve("apps/second"));
            for (String member : List.of("implicit", "second")) {
                Path target = workspace.resolve("apps/" + member + "/target");
                assertTrue(Files.isRegularFile(target.resolve("native/input/" + member + "-0.1.0.jar")));
                assertTrue(Files.isRegularFile(target.resolve("native/" + member)));
            }

            Path declaredWorkspace = writeDeclaredWorkspace(repository.baseUri().toString());
            Path declaredCache = tempDir.resolve("workspace-declared-cache");
            assertSuccess(execute(
                    "resolve", "--workspace",
                    "--cwd", declaredWorkspace.toString(),
                    "--cache-root", declaredCache.toString()));
            String declaredLock = Files.readString(declaredWorkspace.resolve("zolt.lock"));
            assertSuccess(nativeCommand(declaredWorkspace, declaredCache, nativeImage, false, "apps/declared"));
            assertEquals(declaredLock, Files.readString(declaredWorkspace.resolve("zolt.lock")));
            assertDeclaredLoaderRetained(declaredWorkspace.resolve("apps/declared"));
        }
    }

    private void addSpringArtifacts(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "org.springframework.boot",
                "spring-boot-dependencies",
                "3.3.6",
                platformPom());
        repository.addArtifact(
                "org.springframework.boot",
                "spring-boot-loader",
                "3.3.6",
                pom("spring-boot-loader"),
                NativeCommandTestSupport.fakeSpringBootLoaderJar());
        repository.addArtifact(
                "org.springframework.boot",
                "spring-boot",
                "3.3.6",
                pom("spring-boot"),
                NativeCommandTestSupport.fakeSpringBootRecordingAotJar(
                        tempDir.resolve("workspace-spring-aot")));
    }

    private Path writeImplicitWorkspace(String repositoryUrl) throws IOException {
        Path workspace = tempDir.resolve("workspace-spring-native");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-spring-native"

                [workspace.members]
                default = ["apps/implicit"]
                include = ["apps/implicit", "apps/second"]
                """);
        writeMember(workspace.resolve("apps/implicit"), "implicit", repositoryUrl, false);
        writeMember(workspace.resolve("apps/second"), "second", repositoryUrl, false);
        return workspace;
    }

    private Path writeDeclaredWorkspace(String repositoryUrl) throws IOException {
        Path workspace = tempDir.resolve("workspace-declared-native");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-declared-native"

                [workspace.members]
                default = ["apps/declared"]
                include = ["apps/declared"]
                """);
        writeMember(workspace.resolve("apps/declared"), "declared", repositoryUrl, true);
        return workspace;
    }

    private static void writeMember(
            Path member,
            String name,
            String repositoryUrl,
            boolean declaredLoader) throws IOException {
        Files.createDirectories(member);
        String loader = declaredLoader
                ? """

                [dependencies.runtime]
                "org.springframework.boot:spring-boot-loader" = {}
                """
                : "";
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [package]
                mode = "spring-boot"

                [framework.spring-boot]
                native = true
                %s
                """.formatted(name, repositoryUrl, loader));
        Path source = member.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
    }

    private static CommandResult nativeCommand(
            Path workspace,
            Path cache,
            Path nativeImage,
            boolean all,
            String member) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                "native", "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString(),
                "--native-image", nativeImage.toString()));
        if (all) {
            command.add("--all");
        } else {
            command.add("--member");
            command.add(member);
        }
        return execute(command.toArray(String[]::new));
    }

    private static void assertImplicitLoaderExcluded(Path member) throws IOException {
        String aot = Files.readString(member.resolve("target/spring-aot/main/sources/aot-classpath.txt"));
        String nativeLog = Files.readString(member.resolve("target/native/native-image.log"));
        assertTrue(aot.contains("spring-boot-3.3.6.jar"), aot);
        assertFalse(aot.contains("spring-boot-loader-3.3.6.jar"), aot);
        assertFalse(nativeLog.contains("spring-boot-loader-3.3.6.jar"), nativeLog);
        assertTrue(nativeLog.contains("spring-aot/main/classes"), nativeLog);
    }

    private static void assertDeclaredLoaderRetained(Path member) throws IOException {
        String aot = Files.readString(member.resolve("target/spring-aot/main/sources/aot-classpath.txt"));
        String nativeLog = Files.readString(member.resolve("target/native/native-image.log"));
        assertTrue(aot.contains("spring-boot-loader-3.3.6.jar"), aot);
        assertTrue(nativeLog.contains("spring-boot-loader-3.3.6.jar"), nativeLog);
    }

    private static Map<Path, String> configuredOutputHashes(Path workspace) throws IOException {
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (String member : List.of("implicit", "second")) {
            Path target = workspace.resolve("apps/" + member + "/target");
            try (var paths = Files.list(target)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(candidate -> candidate.getFileName().toString().startsWith(member + "-0.1.0"))
                        .sorted()
                        .toList()) {
                    hashes.put(workspace.relativize(path), sha256(path));
                }
            }
        }
        return hashes;
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
}
