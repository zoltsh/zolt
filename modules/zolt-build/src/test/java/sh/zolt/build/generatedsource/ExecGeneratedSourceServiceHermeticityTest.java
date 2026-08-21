package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.config;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.generatingRunner;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.packages;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.service;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.toolPackage;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.writeProjectFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.BuildException;
import sh.zolt.classpath.ResolvedClasspathPackage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three BLOCKER-class hermeticity/isolation review tests for exec steps: per-tool classpath
 * isolation (Hole 1), inheritEnv value-change re-run (Hole 2), and real-path containment of cwd and glob
 * matches against project-local symlink escapes (Hole 3).
 */
final class ExecGeneratedSourceServiceHermeticityTest {
    @TempDir
    private Path projectDir;

    @Test
    void twoToolsWithIncompatibleSharedLibrarySeeOnlyTheirOwnVersion() throws IOException {
        writeProjectFiles(projectDir);
        seedJar(projectDir, "cache/com/example/alpha/1.0.0/alpha-1.0.0.jar");
        seedJar(projectDir, "cache/com/example/beta/1.0.0/beta-1.0.0.jar");
        seedJar(projectDir, "cache/com/example/shared/1.0.0/shared-1.0.0.jar");
        seedJar(projectDir, "cache/com/example/shared/2.0.0/shared-2.0.0.jar");
        // The shared GA is locked at two versions, each tagged for a different tool's isolated closure.
        List<ResolvedClasspathPackage> packages = List.of(
                toolPackage(projectDir, "com.example", "alpha", "1.0.0",
                        "cache/com/example/alpha/1.0.0/alpha-1.0.0.jar", List.of("alpha")),
                toolPackage(projectDir, "com.example", "shared", "1.0.0",
                        "cache/com/example/shared/1.0.0/shared-1.0.0.jar", List.of("alpha")),
                toolPackage(projectDir, "com.example", "beta", "1.0.0",
                        "cache/com/example/beta/1.0.0/beta-1.0.0.jar", List.of("beta")),
                toolPackage(projectDir, "com.example", "shared", "2.0.0",
                        "cache/com/example/shared/2.0.0/shared-2.0.0.jar", List.of("beta")));

        List<List<String>> commands = new ArrayList<>();
        service(projectDir, generatingRunner(commands)).generateMain(projectDir, twoToolConfig(), packages);

        assertEquals(2, commands.size());
        String alphaClasspath = classpathFor(commands, "com.example.AlphaTool");
        String betaClasspath = classpathFor(commands, "com.example.BetaTool");

        assertTrue(alphaClasspath.contains("shared-1.0.0.jar"), alphaClasspath);
        assertFalse(alphaClasspath.contains("shared-2.0.0.jar"), alphaClasspath);
        assertTrue(alphaClasspath.contains("alpha-1.0.0.jar"), alphaClasspath);
        assertFalse(alphaClasspath.contains("beta-1.0.0.jar"), alphaClasspath);

        assertTrue(betaClasspath.contains("shared-2.0.0.jar"), betaClasspath);
        assertFalse(betaClasspath.contains("shared-1.0.0.jar"), betaClasspath);
        assertTrue(betaClasspath.contains("beta-1.0.0.jar"), betaClasspath);
        assertFalse(betaClasspath.contains("alpha-1.0.0.jar"), betaClasspath);
    }

    @Test
    void inheritEnvValueChangeReRunsWhileStableValueSkips() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        var config = config("inheritEnv = [\"DB_URL\"]");
        var packages = packages(projectDir);

        // First run with DB_URL=one: the step runs and writes its fingerprint (folding the value digest).
        service(projectDir, generatingRunner(commands), java.util.Map.of("DB_URL", "one"))
                .generateMain(projectDir, config, packages);
        assertEquals(1, commands.size());

        // Same value: fingerprint matches, step is skipped (no new command).
        service(projectDir, generatingRunner(commands), java.util.Map.of("DB_URL", "one"))
                .generateMain(projectDir, config, packages);
        assertEquals(1, commands.size());

        // Changed inherited value: fingerprint differs, the step must re-run.
        service(projectDir, generatingRunner(commands), java.util.Map.of("DB_URL", "two"))
                .generateMain(projectDir, config, packages);
        assertEquals(2, commands.size());

        // Absent variable digests to a distinct marker, so it too differs from any concrete value.
        service(projectDir, generatingRunner(commands), java.util.Map.of())
                .generateMain(projectDir, config, packages);
        assertEquals(3, commands.size());
    }

    @Test
    void cwdThroughProjectLocalSymlinkEscapingProjectIsRejected() throws IOException {
        writeProjectFiles(projectDir);
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-cwd-");
        createSymlink(projectDir.resolve("web"), outside);

        BuildException exception = assertThrows(BuildException.class, () -> service(projectDir, generatingRunner(new ArrayList<>()))
                .generateMain(projectDir, config("cwd = \"web\""), packages(projectDir)));

        assertTrue(exception.getMessage().contains("escapes the project directory"), exception.getMessage());
    }

    @Test
    void globInputMatchingProjectLocalSymlinkEscapingProjectIsRejected() throws IOException {
        writeProjectFiles(projectDir);
        Path outside = Files.createTempFile(projectDir.getParent(), "outside-input-", ".txt");
        Files.writeString(outside, "secret outside the project\n");
        createSymlink(projectDir.resolve("src/main/jooq/leak.txt"), outside);

        BuildException exception = assertThrows(BuildException.class, () -> service(projectDir, generatingRunner(new ArrayList<>()))
                .generateMain(projectDir, globInputConfig(), packages(projectDir)));

        assertTrue(exception.getMessage().contains("symlink"), exception.getMessage());
    }

    private static String classpathFor(List<List<String>> commands, String mainClass) {
        List<String> command = commands.stream()
                .filter(argv -> argv.contains(mainClass))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no command ran " + mainClass));
        return command.get(command.indexOf("-cp") + 1);
    }

    private static void seedJar(Path projectDir, String relativePath) throws IOException {
        Path jar = projectDir.resolve(relativePath);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar\n");
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static sh.zolt.project.ProjectConfig twoToolConfig() {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.alpha]
                kind = "jvm"
                coordinates = [{ coordinate = "com.example:alpha", version = "1.0.0" }]
                mainClass = "com.example.AlphaTool"

                [generated.tools.beta]
                kind = "jvm"
                coordinates = [{ coordinate = "com.example:beta", version = "1.0.0" }]
                mainClass = "com.example.BetaTool"

                [generated.main.alpha-gen]
                kind = "exec"
                tool = "alpha"
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/alpha"
                produces = "java-sources"

                [generated.main.beta-gen]
                kind = "exec"
                tool = "beta"
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/beta"
                produces = "java-sources"
                """);
    }

    private static sh.zolt.project.ProjectConfig globInputConfig() {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.15"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.model]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/jooq/**"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"
                """);
    }
}
