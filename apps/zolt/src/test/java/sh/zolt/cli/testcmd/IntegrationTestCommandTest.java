package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IntegrationTestCommandTest extends TestCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void integrationTestUsesConfiguredRootsAndSeparateReportsByDefault() throws IOException {
        Path projectDir = tempDir.resolve("integration-demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "integration-demo"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [dependencies]

                [dependencies.test]

                [build.output]
                root = ".zolt/build"
                integration = "target/it-classes"

                [test.integration]
                sources = ["src/it/java"]
                resources = ["src/it/resources"]
                """.formatted(currentJavaMajorVersion()));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        Path mainSource = projectDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(mainSource.getParent());
        Files.writeString(mainSource, "package com.example; public final class App { public static String ok() { return \"ok\"; } }\n");
        Path unitTest = projectDir.resolve("src/test/java/com/example/AppTest.java");
        Files.createDirectories(unitTest.getParent());
        Files.writeString(unitTest, "package com.example; public final class AppTest {}\n");
        Path integrationTest = projectDir.resolve("src/it/java/com/example/AppIT.java");
        Files.createDirectories(integrationTest.getParent());
        Files.writeString(integrationTest, "package com.example; public final class AppIT { String ok() { return App.ok(); } }\n");
        Path resource = projectDir.resolve("src/it/resources/it.properties");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "mode=it\n");

        CommandResult result = execute(
                "--color=always",
                "integration-test",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());
        CommandResult quiet = execute(
                "--quiet",
                "integration-test",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Integration tests passed"));
        assertFalse(result.stdout().contains("\u001B[32mIntegration tests passed\u001B[0m"));
        assertTrue(result.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m"
                + projectDir.resolve(".zolt/build/integration-test-reports").toAbsolutePath().normalize() + "\u001B[0m"));
        assertFalse(result.stdout().contains("\u001B[32mWrote integration test reports to "));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("fake console\n", quiet.stdout());
        assertTrue(Files.exists(projectDir.resolve("target/it-classes/com/example/AppIT.class")));
        assertTrue(Files.exists(projectDir.resolve("target/it-classes/it.properties")));
        assertTrue(Files.exists(projectDir.resolve(".zolt/build/integration-test-reports/TEST-fake-console.xml")));
        assertFalse(Files.exists(projectDir.resolve("target/test-classes/com/example/AppTest.class")));
    }

    @Test
    void integrationTestAcceptsVisibleProjectDirectoryOption() throws IOException {
        Path projectDir = tempDir.resolve("directory-integration-demo");
        Path cacheRoot = tempDir.resolve("cache-directory");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "directory-integration-demo"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [dependencies]

                [dependencies.test]

                [test.integration]
                sources = ["src/it/java"]

                [build.output]
                integration = "it-classes"
                """.formatted(currentJavaMajorVersion()));
        writeJUnitConsoleLockfile(projectDir, cacheRoot);
        Path integrationTest = projectDir.resolve("src/it/java/com/example/AppIT.java");
        Files.createDirectories(integrationTest.getParent());
        Files.writeString(integrationTest, "package com.example; public final class AppIT {}\n");

        CommandResult result = execute(
                "integration-test",
                "--directory", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Integration tests passed"));
        assertTrue(Files.exists(projectDir.resolve("target/it-classes/com/example/AppIT.class")));
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
