package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.writeCurrentProjectLock;
import static sh.zolt.cli.build.BuildCommandTestSupport.writeProjectConfig;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildOutputSafetyCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildRejectsOutputRootInsideAuthoredSourcesWithoutDeletingThem() throws IOException {
        Path projectDir = tempDir.resolve("source-overlap");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [build.output]
                root = "src/main/java"
                """);
        writeCurrentProjectLock(projectDir);
        String content = "package com.example; public final class Important {}\n";
        Path source = projectDir.resolve("src/main/java/classes/com/example/Important.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);

        CommandResult result = execute(
                "build",
                "--no-build-cache",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("source-overlap-cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unsafe compile output layout"), result.stderr());
        assertTrue(result.stderr().contains("[build.output].main"), result.stderr());
        assertTrue(result.stderr().contains("[build].sources[0]"), result.stderr());
        assertTrue(Files.isRegularFile(source));
        assertEquals(content, Files.readString(source));
    }
}
