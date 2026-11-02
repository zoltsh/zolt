package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RunWorkingDirectoryIntegrationTest {
    @TempDir
    private Path root;

    @ParameterizedTest
    @ValueSource(strings = {"run", "run-package"})
    void standaloneApplicationReadsAndWritesInSelectedDirectory(String command) throws Exception {
        Path project = project(root.resolve("selected project"));
        invoke(command, project, List.of());
        assertEquals("project", Files.readString(project.resolve("output.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "run-package"})
    void workspaceApplicationUsesMemberDirectoryRatherThanWorkspaceOrCaller(String command) throws Exception {
        Path workspace = root.resolve("workspace");
        Path member = project(workspace.resolve("apps/demo"));
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "cwd-workspace"
                [workspace.members]
                include = ["apps/demo"]
                """);
        Files.writeString(workspace.resolve("input.txt"), "workspace");

        invoke(command, workspace, List.of("--workspace", "--member", "apps/demo"));

        assertEquals("project", Files.readString(member.resolve("output.txt")));
        assertFalse(Files.exists(workspace.resolve("output.txt")));
    }

    private Path project(Path path) throws Exception {
        Path source = path.resolve("src/main/java/review/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(path.resolve("zolt.toml"), CliTestSupport.memberConfig("cwd-app")
                + "main = \"review.Main\"\n");
        Files.writeString(path.resolve("input.txt"), "project");
        Files.writeString(source, """
                package review;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        String value = java.nio.file.Files.readString(java.nio.file.Path.of("input.txt"));
                        java.nio.file.Files.writeString(java.nio.file.Path.of("output.txt"), value);
                        System.out.println("relative-io=" + value);
                    }
                }
                """);
        return path;
    }

    private void invoke(String command, Path directory, List<String> selection) throws Exception {
        Path caller = root.resolve("unrelated caller");
        Files.createDirectories(caller);
        Files.writeString(caller.resolve("input.txt"), "caller");
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", cliClasspath(), "sh.zolt.cli.ZoltCli", "--no-progress", "--color", "never",
                command, "--directory", directory.toString(), "--cache-root", root.resolve("cache").toString()));
        arguments.addAll(selection);
        Path log = root.resolve(command + ".log");
        ProcessBuilder builder = new ProcessBuilder(arguments).directory(caller.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ZOLT_USER_HOME", root.resolve("user-home").toString());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "CLI process did not finish");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("relative-io=project"), output);
            assertFalse(Files.exists(caller.resolve("output.txt")), "application wrote into caller directory");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static String cliClasspath() throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        for (ClassLoader loader = RunWorkingDirectoryIntegrationTest.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        entries.add(Path.of(url.toURI()).toAbsolutePath().normalize().toString());
                    }
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }
}
