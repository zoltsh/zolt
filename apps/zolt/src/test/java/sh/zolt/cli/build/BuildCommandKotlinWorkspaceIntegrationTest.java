package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler CLI coverage for Kotlin members under workspace scheduling and diagnostics. */
final class BuildCommandKotlinWorkspaceIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void attributesKotlinCompilerFailureToItsWorkspaceMember() throws Exception {
        Path workspace = tempDir.resolve("compiler-failure");
        Path cache = tempDir.resolve("compiler-failure-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri(), List.of("apps/good", "apps/bad"));
            writeKotlinMember(workspace.resolve("apps/good"), "good", """
                    package probe
                    object Good { fun value(): String = "good" }
                    """, "");
            writeKotlinMember(workspace.resolve("apps/bad"), "bad", """
                    package probe
                    object Bad { fun value(): String = 42 }
                    """, "");

            assertResolveSucceeds(workspace, cache);
            CommandResult build = build(workspace, cache);

            assertEquals(1, build.exitCode(), build.stderr());
            assertTrue(build.stderr().contains("Kotlin main compilation failed"), build.stderr());
            assertTrue(
                    build.stderr().contains("Workspace member `apps/bad` failed to compile."),
                    build.stderr());
            assertFalse(build.stderr().contains("\tat "), build.stderr());
        }
    }

    @Test
    void forbiddenWorkspaceDependenciesPreserveKotlinMemberOutput() throws Exception {
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            for (String section : List.of("dependencies", "dependencies.api")) {
                Path workspace = tempDir.resolve(section.replace('.', '-'));
                Path cache = tempDir.resolve(section.replace('.', '-') + "-cache");
                writeWorkspace(workspace, repository.baseUri(), List.of("modules/lib", "apps/kotlin"));
                writeJavaLibrary(workspace.resolve("modules/lib"));
                writeKotlinMember(
                        workspace.resolve("apps/kotlin"),
                        "kotlin",
                        "package probe\nobject App { fun value(): String = \"ok\" }\n",
                        workspaceDependency(section));
                Path sentinel = workspace.resolve("apps/kotlin/target/classes/preserved.bin");
                Files.createDirectories(sentinel.getParent());
                byte[] expected = ("preserved-" + section).getBytes(StandardCharsets.UTF_8);
                Files.write(sentinel, expected);

                assertResolveSucceeds(workspace, cache);
                CommandResult build = build(workspace, cache);

                assertEquals(1, build.exitCode(), build.stderr());
                assertTrue(
                        build.stderr().contains("compile-scoped workspace dependencies are configured"),
                        build.stderr());
                assertTrue(
                        build.stderr().contains("Workspace member `apps/kotlin` failed to compile."),
                        build.stderr());
                assertArrayEquals(expected, Files.readAllBytes(sentinel));
            }
        }
    }

    private static void assertResolveSucceeds(Path workspace, Path cache) {
        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
    }

    private static CommandResult build(Path workspace, Path cache) {
        return execute(
                "build",
                "--workspace",
                "--all",
                "--no-build-cache",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeWorkspace(Path workspace, URI repository, List<String> members)
            throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-workspace"

                [workspace.members]
                include = %s

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(tomlArray(members), repository));
    }

    private static void writeJavaLibrary(Path directory) throws Exception {
        Path source = directory.resolve("src/main/java/probe/Lib.java");
        Files.createDirectories(source.getParent());
        Files.writeString(directory.resolve("zolt.toml"), project("lib"));
        Files.writeString(source, "package probe; public final class Lib {}\n");
    }

    private static void writeKotlinMember(
            Path directory,
            String name,
            String sourceContent,
            String workspaceDependency) throws Exception {
        Path source = directory.resolve("src/main/kotlin/probe/"
                + Character.toUpperCase(name.charAt(0)) + name.substring(1) + ".kt");
        Files.createDirectories(source.getParent());
        Files.writeString(directory.resolve("zolt.toml"), project(name) + """

                [toolchain.kotlin]
                version = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                %s
                """.formatted(
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                workspaceDependency));
        Files.writeString(source, sourceContent);
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "probe"
                java = %s
                """.formatted(name, Runtime.version().feature());
    }

    private static String workspaceDependency(String section) {
        if ("dependencies".equals(section)) {
            return "\"probe:lib\" = { workspace = true }";
        }
        return "\n[dependencies.api]\n\"probe:lib\" = { workspace = true }";
    }

    private static String tomlArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
}
