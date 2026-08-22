package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfParityCommandTest extends PackageCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void selfParityReadsSelectedDirectory() {
        Path projectDir = tempDir.resolve("selected");

        CommandResult result = execute(
                "self-parity",
                "--directory", projectDir.toString(),
                "--bootstrap-jar", "build/bootstrap-zolt/zolt-bootstrap.jar",
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Self-hosting parity requires bootstrap jar"));
        assertTrue(result.stderr().contains(projectDir.resolve("build/bootstrap-zolt/zolt-bootstrap.jar").toString()));
    }

    @Test
    void selfParityKeepsHiddenCwdCompatibility() {
        Path projectDir = tempDir.resolve("hidden-cwd");

        CommandResult result = execute(
                "self-parity",
                "--cwd", projectDir.toString(),
                "--bootstrap-jar", "build/bootstrap-zolt/zolt-bootstrap.jar",
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(projectDir.resolve("build/bootstrap-zolt/zolt-bootstrap.jar").toString()));
    }

    @Test
    void selfParityMismatchStylesErrorPrefixAndSuppressesDuplicateHandlerError() throws IOException {
        Path projectDir = tempDir.resolve("mismatch");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);
        writeBootstrapJar(projectDir.resolve("bootstrap.jar"));

        CommandResult result = execute(
                "--color=always",
                "self-parity",
                "--directory", projectDir.toString(),
                "--bootstrap-jar", "bootstrap.jar",
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("\u001B[31merror:\u001B[0m Self-hosting parity failed"));
        assertFalse(result.stderr().contains("\u001B[31merror: Self-hosting parity failed"));
        assertTrue(result.stderr().contains("Missing from Zolt-built jar:"));
        assertTrue(result.stderr().contains("only-bootstrap.txt"));
        assertFalse(result.stderr().contains("\u001B[31mMissing"));
        assertFalse(result.stderr().contains("\u001B[31m  - only-bootstrap.txt"));
        assertTrue(
                result.stderr().contains("Extra in Zolt-built jar:"),
                () -> "the trailing entry block must be flushed before the command fails: " + result.stderr());
        assertTrue(
                result.stderr().contains("com/example/Main.class"),
                () -> "the trailing entry block must be flushed before the command fails: " + result.stderr());
        assertEquals(1, occurrences(result.stderr(), "Self-hosting parity failed"));
    }

    @Test
    void selfParityUsesModernHumanOutputControlsOnSuccess() throws IOException {
        Path colorProject = tempDir.resolve("color-parity");
        Path quietProject = tempDir.resolve("quiet-parity");
        writeParityProject(colorProject);
        writeParityProject(quietProject);
        CommandResult colorPackage = execute(
                "package",
                "--directory", colorProject.toString(),
                "--cache-root", tempDir.resolve("color-package-cache").toString());
        CommandResult quietPackage = execute(
                "package",
                "--directory", quietProject.toString(),
                "--cache-root", tempDir.resolve("quiet-package-cache").toString());
        assertEquals(0, colorPackage.exitCode(), colorPackage.stderr());
        assertEquals(0, quietPackage.exitCode(), quietPackage.stderr());

        CommandResult color = execute(
                "--color=always",
                "self-parity",
                "--directory", colorProject.toString(),
                "--bootstrap-jar", "target/demo-0.1.0.jar",
                "--cache-root", tempDir.resolve("color-parity-cache").toString());
        CommandResult quiet = execute(
                "--quiet",
                "self-parity",
                "--directory", quietProject.toString(),
                "--bootstrap-jar", "target/demo-0.1.0.jar",
                "--cache-root", tempDir.resolve("quiet-parity-cache").toString());

        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("Self-hosting parity status: \u001B[32mok\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mSelf-hosting\u001B[0m parity status"));
        assertTrue(color.stdout().contains("Bootstrap jar: " + colorProject.resolve("target/demo-0.1.0.jar")));
        assertTrue(color.stdout().contains("Zolt-built jar: " + colorProject.resolve("target/demo-0.1.0.jar")));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Jar entries match"));
        assertFalse(color.stdout().contains("\u001B[32mok: Jar entries match"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
    }

    private static void writeParityProject(Path projectDir) throws IOException {
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);
    }

    private static void writeBootstrapJar(Path path) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("only-bootstrap.txt"));
            zip.write("bootstrap only\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int index = text.indexOf(value);
        while (index >= 0) {
            count++;
            index = text.indexOf(value, index + value.length());
        }
        return count;
    }
}
