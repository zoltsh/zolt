package sh.zolt.cli.build.ksp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Cold-store, generated-lane regeneration, and compiled test-output restoration proof for KSP2. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KspTestBuildCacheIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void restoresCompiledKspTestsAfterRegeneratingOwnedLanes() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            configureBuildCache(fakeUserHome);
            Path project = tempDir.resolve("project");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KspCliFixture.publish(repository, tempDir.resolve("processor"));
            KspTestCommandIntegrationTest.writeProject(project, repository);
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();

            CommandResult first = test(project, artifactCache);
            Path generated = project.resolve("target/generated/ksp/test/symbols");
            Path testOutput = project.resolve("target/test-classes");
            Path authoredClass = testOutput.resolve("com/example/KspGeneratedTest.class");
            Path kotlinClass = testOutput.resolve("com/example/GeneratedKspMessage.class");
            Path javaClass = testOutput.resolve("com/example/GeneratedJavaMessage.class");

            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed"), first.stdout());
            assertTiming(first, "full");
            byte[] authoredBytes = Files.readAllBytes(authoredClass);
            byte[] kotlinBytes = Files.readAllBytes(kotlinClass);
            byte[] javaBytes = Files.readAllBytes(javaClass);

            deleteTree(project.resolve("target"));
            CommandResult restored = test(project, artifactCache);

            assertEquals(0, restored.exitCode(), restored.stderr());
            assertTrue(restored.stdout().contains("Tests passed"), restored.stdout());
            assertTiming(restored, "restored");
            assertArrayEquals(authoredBytes, Files.readAllBytes(authoredClass));
            assertArrayEquals(kotlinBytes, Files.readAllBytes(kotlinClass));
            assertArrayEquals(javaBytes, Files.readAllBytes(javaClass));
            assertTrue(Files.isRegularFile(
                    generated.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
            assertTrue(Files.isRegularFile(
                    generated.resolve("java/com/example/GeneratedJavaMessage.java")));
            assertEquals(
                    "test-ksp-resource\n",
                    Files.readString(testOutput.resolve("META-INF/ksp-cli.txt")));
            assertFalse(Files.exists(testOutput.resolve(".zolt-incremental-test.state")));

            CommandResult warm = test(project, artifactCache);

            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTiming(warm, "skipped");
            assertEquals(Map.of(), repository.authorizations());
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static void configureBuildCache(Path fakeUserHome) throws IOException {
        Path globalDirectory = fakeUserHome.resolve(".zolt");
        Files.createDirectories(globalDirectory);
        Files.writeString(globalDirectory.resolve("config.toml"), """
                version = 1

                [buildCache]
                enabled = true
                dir = "build-cache"
                """);
    }

    private static CommandResult test(Path project, Path artifactCache) {
        return execute(
                "test",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", artifactCache.toString(),
                "--no-progress");
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing test compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
