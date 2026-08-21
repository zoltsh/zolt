package sh.zolt.cli.build;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CleanCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void cleanDeletesBuildOutputWithoutDeletingCache() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("target/classes/Main.class"), "compiled");
        Files.createDirectories(projectDir.resolve(".zolt/cache"));
        Files.writeString(projectDir.resolve(".zolt/cache/artifact.jar"), "cached");

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 1 build output paths"));
        assertFalse(Files.exists(projectDir.resolve("target")));
        assertTrue(Files.exists(projectDir.resolve(".zolt/cache/artifact.jar")));
    }

    @Test
    void cleanAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("target/classes/Main.class"), "compiled");

        CommandResult result = execute("clean", "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 1 build output paths"));
        assertFalse(Files.exists(projectDir.resolve("target")));
    }

    @Test
    void cleanUsesModernHumanOutputControls() throws IOException {
        Path colorProject = tempDir.resolve("color-clean");
        writeProjectConfig(colorProject, "https://repo.maven.apache.org/maven2");
        Files.createDirectories(colorProject.resolve("target/classes"));
        Files.writeString(colorProject.resolve("target/classes/Main.class"), "compiled");
        Path quietProject = tempDir.resolve("quiet-clean");
        writeProjectConfig(quietProject, "https://repo.maven.apache.org/maven2");
        Files.createDirectories(quietProject.resolve("target/classes"));
        Files.writeString(quietProject.resolve("target/classes/Main.class"), "compiled");

        CommandResult color = execute("--color=always", "clean", "--cwd", colorProject.toString());
        CommandResult quiet = execute("--quiet", "clean", "--cwd", quietProject.toString());

        assertEquals(0, color.exitCode());
        assertTrue(color.stdout().contains("\u001B[32mDeleted\u001B[0m 1 build output paths"));
        assertTrue(color.stdout().contains("\u001B[32mDeleted\u001B[0m " + colorProject.resolve("target")));
        assertFalse(color.stdout().contains("\u001B[32mDeleted 1 build output paths"));
        assertFalse(color.stdout().contains("\u001B[32mDeleted " + colorProject.resolve("target")));
        assertEquals(0, quiet.exitCode());
        assertEquals("", quiet.stdout());
        assertEquals("", quiet.stderr());
        assertFalse(Files.exists(colorProject.resolve("target")));
        assertFalse(Files.exists(quietProject.resolve("target")));
    }

    @Test
    void cleanDeletesOutputRootWithoutDeletingMavenTargetOrGradleBuild() throws IOException {
        Path projectDir = tempDir.resolve("migration-demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        .replace("output = \"target/classes\"", "outputRoot = \".zolt/build\"\noutput = \".zolt/build/classes\"")
                        .replace("testOutput = \"target/test-classes\"", "testOutput = \".zolt/build/test-classes\""));
        Files.createDirectories(projectDir.resolve(".zolt/build/classes"));
        Files.writeString(projectDir.resolve(".zolt/build/classes/Main.class"), "compiled");
        Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("target/classes/MavenMain.class"), "maven");
        Files.createDirectories(projectDir.resolve("build/classes/java/main"));
        Files.writeString(projectDir.resolve("build/classes/java/main/GradleMain.class"), "gradle");

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 1 build output paths"));
        assertFalse(Files.exists(projectDir.resolve(".zolt/build")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/MavenMain.class")));
        assertTrue(Files.exists(projectDir.resolve("build/classes/java/main/GradleMain.class")));
    }

    @Test
    void cleanDeletesQuarkusOutputLayoutWhenEnabled() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        .replace("output = \"target/classes\"", "output = \"out/main\"")
                        .replace("testOutput = \"target/test-classes\"", "testOutput = \"out/test\""));
        enableQuarkus(projectDir);
        Files.createDirectories(projectDir.resolve("out/main"));
        Files.writeString(projectDir.resolve("out/main/Main.class"), "compiled");
        Files.createDirectories(projectDir.resolve("target/quarkus"));
        Files.writeString(projectDir.resolve("target/quarkus/zolt-augmentation.properties"), "metadata");
        Files.createDirectories(projectDir.resolve("target/quarkus-app"));
        Files.writeString(projectDir.resolve("target/quarkus-app/quarkus-run.jar"), "jar");

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 3 build output paths"));
        assertFalse(Files.exists(projectDir.resolve("out/main")));
        assertFalse(Files.exists(projectDir.resolve("target/quarkus")));
        assertFalse(Files.exists(projectDir.resolve("target/quarkus-app")));
    }

    @Test
    void cleanDeletesSpringBootAotOutputLayoutWhenNativeIsEnabled() throws IOException {
        Path projectDir = tempDir.resolve("spring-aot-clean");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        .replace("output = \"target/classes\"", "output = \"out/main\"")
                        .replace("testOutput = \"target/test-classes\"", "testOutput = \"out/test\""));
        enableSpringBootNative(projectDir);
        Files.createDirectories(projectDir.resolve("target/spring-aot/main/sources/com/example"));
        Files.writeString(projectDir.resolve("target/spring-aot/main/sources/com/example/Application__BeanDefinitions.java"), "aot");
        Files.createDirectories(projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image"));
        Files.writeString(projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/reflect-config.json"), "[]");
        Files.createDirectories(projectDir.resolve("target/spring-aot/main/classes"));
        Files.writeString(projectDir.resolve("target/spring-aot/main/classes/Application__BeanDefinitions.class"), "class");

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 1 build output paths"));
        assertFalse(Files.exists(projectDir.resolve("target/spring-aot")));
    }

    @Test
    void cleanDeletesProtobufGeneratedOutputs() throws IOException {
        Path projectDir = tempDir.resolve("protobuf-clean");
        Files.createDirectories(projectDir.resolve("target/generated/sources/protobuf/com/example"));
        Files.writeString(projectDir.resolve("target/generated/sources/protobuf/com/example/HelloRequest.java"), "generated");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "protobuf-clean"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build.output]
                main = "out/classes"
                test = "out/test-classes"

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                output = "target/generated/sources/protobuf"
                inputs = ["src/main/proto/greeter.proto"]
                """.formatted(currentJavaMajorVersion()));

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted 1 build output paths"));
        assertFalse(Files.exists(projectDir.resolve("target/generated/sources/protobuf")));
    }

    @Test
    void cleanHandlesMissingTargetCleanly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("Nothing to clean\n", result.stdout());
    }

    @Test
    void cleanWorkspaceDeletesSelectedMembersAndDependencies() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-clean");
        writeWorkspaceConfig(workspaceDir, """
                [workspace]
                name = "workspace-clean"

                [workspace.members]
                include = ["apps/api", "modules/core", "apps/worker"]
                """);
        writeWorkspaceMember(workspaceDir, "modules/core", "core", "");
        writeWorkspaceMember(workspaceDir, "apps/api", "api", """

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        writeWorkspaceMember(workspaceDir, "apps/worker", "worker", "");
        writeOutput(workspaceDir, "modules/core/target/classes/Core.class");
        writeOutput(workspaceDir, "apps/api/target/classes/Api.class");
        writeOutput(workspaceDir, "apps/worker/target/classes/Worker.class");
        writeOutput(workspaceDir, "apps/api/.zolt/cache/artifact.jar");
        writeOutput(workspaceDir, ".zolt/cache/artifact.jar");

        CommandResult result = execute(
                "clean",
                "--workspace",
                "--member",
                "apps/api",
                "--cwd",
                workspaceDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Deleted 2 workspace build output paths across 2 members"));
        assertTrue(result.stdout().contains("Deleted modules/core " + workspaceDir.resolve("modules/core/target")));
        assertTrue(result.stdout().contains("Deleted apps/api " + workspaceDir.resolve("apps/api/target")));
        assertFalse(Files.exists(workspaceDir.resolve("modules/core/target")));
        assertFalse(Files.exists(workspaceDir.resolve("apps/api/target")));
        assertTrue(Files.exists(workspaceDir.resolve("apps/worker/target/classes/Worker.class")));
        assertTrue(Files.exists(workspaceDir.resolve("apps/api/.zolt/cache/artifact.jar")));
        assertTrue(Files.exists(workspaceDir.resolve(".zolt/cache/artifact.jar")));
        assertFalse(Files.exists(workspaceDir.resolve("zolt.lock")));
    }

    @Test
    void cleanWorkspaceHandlesMissingOutputsCleanly() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-noop-clean");
        writeWorkspaceConfig(workspaceDir, """
                [workspace]
                name = "workspace-noop-clean"

                [workspace.members]
                include = ["apps/api"]
                """);
        writeWorkspaceMember(workspaceDir, "apps/api", "api", "");

        CommandResult result = execute("clean", "--workspace", "--cwd", workspaceDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("Nothing to clean\n", result.stdout());
    }

    @Test
    void cleanWorkspaceUsesModernHumanOutputControls() throws IOException {
        Path colorWorkspace = tempDir.resolve("workspace-color-clean");
        writeWorkspaceConfig(colorWorkspace, """
                [workspace]
                name = "workspace-color-clean"

                [workspace.members]
                include = ["apps/api"]
                """);
        writeWorkspaceMember(colorWorkspace, "apps/api", "api", "");
        writeOutput(colorWorkspace, "apps/api/target/classes/Api.class");
        Path quietWorkspace = tempDir.resolve("workspace-quiet-clean");
        writeWorkspaceConfig(quietWorkspace, """
                [workspace]
                name = "workspace-quiet-clean"

                [workspace.members]
                include = ["apps/api"]
                """);
        writeWorkspaceMember(quietWorkspace, "apps/api", "api", "");
        writeOutput(quietWorkspace, "apps/api/target/classes/Api.class");

        CommandResult color = execute(
                "--color=always",
                "clean",
                "--workspace",
                "--cwd",
                colorWorkspace.toString());
        CommandResult quiet = execute(
                "--quiet",
                "clean",
                "--workspace",
                "--cwd",
                quietWorkspace.toString());

        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("\u001B[32mDeleted\u001B[0m 1 workspace build output paths across 1 members"));
        assertTrue(color.stdout().contains("\u001B[32mDeleted\u001B[0m apps/api " + colorWorkspace.resolve("apps/api/target")));
        assertFalse(color.stdout().contains("\u001B[32mDeleted 1 workspace"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertFalse(Files.exists(colorWorkspace.resolve("apps/api/target")));
        assertFalse(Files.exists(quietWorkspace.resolve("apps/api/target")));
    }

    private static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    private static void writeWorkspaceConfig(Path workspaceDir, String content) throws IOException {
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), content);
    }

    private static void writeWorkspaceMember(
            Path workspaceDir,
            String memberPath,
            String name,
            String extraToml) throws IOException {
        Path memberDir = workspaceDir.resolve(memberPath);
        Files.createDirectories(memberDir);
        Files.writeString(memberDir.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [repositories]
                central = "https://repo.maven.apache.org/maven2"
                %s
                """.formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private static void writeOutput(Path root, String path) throws IOException {
        Path output = root.resolve(path);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "output");
    }

    private static void enableQuarkus(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "quarkus"
                """);
    }

    private static void enableSpringBootNative(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [framework.spring-boot]
                native = true
                """);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
