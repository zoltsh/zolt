package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.createJarWithEntry;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.nestedJarName;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeMainSource;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeProjectConfig;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeSpringBootLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageSpringBootJarCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageBuildsSpringBootJarWhenLoaderIsResolved() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "spring-boot"
                """);
        writeSpringBootLockfile(projectDir, cacheRoot);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        CommandResult result = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as spring-boot jar"));
        assertTrue(result.stdout().contains("Run with: java -jar " + jarPath));
        assertTrue(result.stdout().contains("Run with Zolt: zolt run-package --mode spring-boot -- [args]"));
        assertTrue(result.stdout().contains("Spring Boot jar: dependencies are nested under BOOT-INF/lib."));
        assertFalse(result.stdout().contains("Thin jar: dependencies are not bundled."));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/example/Main.class"));
            assertNotNull(jar.getEntry(
                    "BOOT-INF/lib/"
                            + nestedJarName(
                                    "com.example",
                                    "runtime-lib",
                                    "1.0.0")));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class"));
            assertEquals(
                    "com.example.Main",
                    jar.getManifest().getMainAttributes().getValue("Start-Class"));
        }
    }

    @Test
    void packageSpringBootGuidanceLabelsStayPlainWhenColorIsForced() throws IOException {
        Path projectDir = tempDir.resolve("color-demo");
        Path cacheRoot = tempDir.resolve("color-cache");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "spring-boot"
                """);
        writeSpringBootLockfile(projectDir, cacheRoot);
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");

        CommandResult result = execute(
                "--color=always",
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Packaged 1 compiled files as spring-boot jar"));
        assertTrue(result.stdout().contains("Run with: "));
        assertTrue(result.stdout().contains("Run with Zolt: "));
        assertFalse(result.stdout().contains("\u001B[32mRun with"));
        assertFalse(result.stdout().contains("\u001B[36mRun with"));
    }

    @Test
    void packageReportsMissingSpringBootLoaderClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "spring-boot"
                """);
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");

        CommandResult result = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("requires `org.springframework.boot:spring-boot-loader`"));
        assertTrue(result.stderr().contains("run `zolt resolve`"));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }
}
