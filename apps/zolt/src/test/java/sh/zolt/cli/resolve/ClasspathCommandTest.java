package sh.zolt.cli.resolve;

import sh.zolt.cli.CliTestSupport;


import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.ContentAddressedLockTestSupport.cachedJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClasspathCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void classpathRejectsStaleExistingLockfileWhenProjectConfigExists() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("classpath-stale-lock");
            Path cacheRoot = tempDir.resolve("classpath-stale-lock-cache");
            ClasspathCommandTestSupport.writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of("com.example:app", "1.0.0"));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            ClasspathCommandTestSupport.writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of(
                    "com.example:app", "1.0.0",
                    "com.example:extra", "1.0.0"));

            CommandResult result = execute(
                    "classpath",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString(),
                    "compile");

            assertEquals(0, resolve.exitCode());
            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("zolt.lock is out of date"));
            assertTrue(result.stderr().contains("Run `zolt resolve` to refresh it"));
            assertEquals("", result.stdout());
        }
    }

    @Test
    void classpathPrintsRequestedClasspathFromLockfile() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        ClasspathCommandTestSupport.writeLockfile(projectDir, cacheRoot, """
                version = 1

                [[package]]
                id = "com.example:compile-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/compile-lib/1.0.0/compile-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:test-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/test-lib/1.0.0/test-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:processor-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor-lib/1.0.0/processor-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:test-processor-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                jar = "com/example/test-processor-lib/1.0.0/test-processor-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.0/quarkus-rest-deployment-3.33.0.jar"
                dependencies = []
                """);

        Path lockfile = projectDir.resolve("zolt.lock");
        Path compileJar = cachedJar(lockfile, cacheRoot, "com.example:compile-lib");
        Path runtimeJar = cachedJar(lockfile, cacheRoot, "com.example:runtime-lib");
        Path testJar = cachedJar(lockfile, cacheRoot, "com.example:test-lib");
        Path processorJar = cachedJar(lockfile, cacheRoot, "com.example:processor-lib");
        Path testProcessorJar = cachedJar(lockfile, cacheRoot, "com.example:test-processor-lib");
        Path quarkusDeploymentJar = cachedJar(
                lockfile, cacheRoot, "io.quarkus:quarkus-rest-deployment");

        CommandResult compile = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "compile");
        CommandResult directoryCompile = CliTestSupport.execute(
                "classpath",
                "--directory", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "compile");
        CommandResult runtime = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "runtime");
        CommandResult test = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "test");
        CommandResult processor = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "processor");
        CommandResult testProcessor = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "test-processor");
        CommandResult quarkusDeployment = CliTestSupport.execute(
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "quarkus-deployment");
        CommandResult colorAlwaysCompile = CliTestSupport.execute(
                "--color=always",
                "--progress=always",
                "classpath",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "compile");

        assertEquals(0, compile.exitCode());
        assertEquals(compileJar + System.lineSeparator(), compile.stdout());
        assertEquals(0, directoryCompile.exitCode());
        assertEquals(compile.stdout(), directoryCompile.stdout());
        assertEquals(0, runtime.exitCode());
        assertEquals(
                compileJar + File.pathSeparator + runtimeJar + System.lineSeparator(),
                runtime.stdout());
        assertEquals(0, test.exitCode());
        assertEquals(
                compileJar + File.pathSeparator + runtimeJar + File.pathSeparator + testJar + System.lineSeparator(),
                test.stdout());
        assertEquals(0, processor.exitCode());
        assertEquals(processorJar + System.lineSeparator(), processor.stdout());
        assertEquals(0, testProcessor.exitCode());
        assertEquals(testProcessorJar + System.lineSeparator(), testProcessor.stdout());
        assertEquals(0, quarkusDeployment.exitCode());
        assertEquals(quarkusDeploymentJar + System.lineSeparator(), quarkusDeployment.stdout());
        assertEquals(0, colorAlwaysCompile.exitCode());
        assertEquals("", colorAlwaysCompile.stderr());
        assertFalse(colorAlwaysCompile.stdout().contains("\u001B["));
        assertEquals(compile.stdout(), colorAlwaysCompile.stdout());
    }
}
