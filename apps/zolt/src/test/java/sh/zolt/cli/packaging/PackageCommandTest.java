package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
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

final class PackageCommandTest extends PackageCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void packageBuildsAndWritesJarWithManifest() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
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
                "--cache-root", tempDir.resolve("cache").toString());

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Resolved dependencies because zolt.lock was missing"));
        assertTrue(result.stdout().contains("Packaged 1 compiled files as jar"));
        assertTrue(result.stdout().contains("Included Main-Class manifest entry"));
        assertTrue(result.stdout().contains("Run with: java -jar " + jarPath));
        assertTrue(result.stdout().contains("Run with dependencies: zolt run-package -- [args]"));
        assertTrue(result.stdout().contains("Jar: dependencies are not bundled."));
        assertTrue(result.stdout().contains(
                "→ wrote " + projectDir.resolve("target/demo-0.1.0.runtime-classpath")));
        assertTrue(result.stdout().contains("→ wrote " + jarPath));
        assertTrue(result.stdout().contains("→ wrote " + jarPath + ".zolt-package.json"));
        assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        assertTrue(Files.exists(projectDir.resolve("target/demo-0.1.0.runtime-classpath")));
        assertTrue(Files.exists(projectDir.resolve("target/demo-0.1.0.jar.zolt-package.json")));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertEquals(
                    "com.example.Main",
                    jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        }
    }

    @Test
    void packageModeOverrideRejectsResolutionChangingModeForCurrentCommandOnly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [package]
                mode = "spring-boot"
                """);
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
                "--mode", "jar",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("changes dependency-resolution tooling"));
        assertTrue(result.stderr().contains("Set `[package].mode = \"jar\"`"));
        assertTrue(result.stderr().contains("run `zolt resolve`"));
        assertTrue(Files.readString(projectDir.resolve("zolt.toml")).contains("mode = \"spring-boot\""));
        assertFalse(Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void packageModeOverrideBuildsUberJar() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
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
                "--mode", "uber-jar",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as uber-jar"));
        assertTrue(result.stdout().contains("Run as a self-contained jar: java -jar " + jarPath + " [args]"));
        assertTrue(result.stdout().contains(
                "Uber jar: runtime dependency classes and resources are merged into the archive root."));
        assertTrue(Files.exists(jarPath));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("com/example/Main.class"));
        }
    }

    @Test
    void packageUberGuidanceLabelStaysPlainWhenColorIsForced() throws IOException {
        Path projectDir = tempDir.resolve("color-uber");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, "package com.example; public final class Main {}\n");

        CommandResult result = execute(
                "--color=always",
                "package",
                "--mode", "uber-jar",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("color-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\u001B[32m✔\u001B[0m Packaged 1 compiled files as uber-jar"));
        assertTrue(result.stdout().contains("Run as a self-contained jar: "));
        assertFalse(result.stdout().contains("\u001B[32mRun as"));
        assertFalse(result.stdout().contains("\u001B[36mRun as"));
    }

    @Test
    void packageModeOverrideLeavesExistingLockfileUnchangedForCurrentCommand() throws IOException {
        Path projectDir = tempDir.resolve("demo-existing-lock");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);
        CommandResult resolve = execute(
                "resolve",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
        String thinLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        CommandResult result = execute(
                "package",
                "--mode", "uber-jar",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Packaged 1 compiled files as uber-jar"));
        assertEquals(thinLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }


    @Test
    void migrationFixturePackagesUnderOutputRootWithoutTouchingMavenTarget() throws IOException {
        Path projectDir = tempDir.resolve("migration-fixture");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>0.1.0</version>
                </project>
                """);
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml")) + """

                [build.output]
                root = ".zolt/build"
                """);
        Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(projectDir.resolve("target/classes/MavenMain.class"), "maven output\n");
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);

        CommandResult packageResult = execute(
                "package",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        Path jarPath = projectDir.resolve(".zolt/build/demo-0.1.0.jar");
        assertEquals(0, packageResult.exitCode(), packageResult.stderr());
        assertTrue(packageResult.stdout().contains("→ wrote " + jarPath));
        assertTrue(Files.exists(jarPath));
        assertTrue(Files.exists(projectDir.resolve(".zolt/build/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/MavenMain.class")));
        assertTrue(Files.exists(projectDir.resolve("pom.xml")));

        CommandResult cleanResult = execute("clean", "--cwd", projectDir.toString());

        assertEquals(0, cleanResult.exitCode(), cleanResult.stderr());
        assertFalse(Files.exists(projectDir.resolve(".zolt/build")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/MavenMain.class")));
        assertTrue(Files.exists(projectDir.resolve("pom.xml")));
    }
}
