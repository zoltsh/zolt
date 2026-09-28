package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** CLI canary for hermetic Kotlin resolution, compilation, reuse, and launch. */
final class BuildCommandKotlinOfflineIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesBuildsRunsAndReusesRealKotlinMainOffline() throws Exception {
        Path projectDirectory = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");

        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.writeProject(projectDirectory, repository.baseUri());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", onlineCache.toString());

            assertEquals(0, resolve.exitCode(), resolve.stderr());
            assertTrue(resolve.stdout().contains("wrote " + projectDirectory.resolve("zolt.lock")));
            String lock = Files.readString(projectDirectory.resolve("zolt.lock"));
            assertTrue(lock.contains("id = \"org.jetbrains.kotlin:kotlin-compiler-embeddable\""));
            assertTrue(lock.contains("scope = \"tool-kotlin\""));
            repository.clearAuthorizations();

            Files.move(onlineCache, offlineCache);
            byte[] lockBeforeOfflineResolve = Files.readAllBytes(projectDirectory.resolve("zolt.lock"));
            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());
            assertTrue(offlineResolve.stdout().contains("verified " + projectDirectory.resolve("zolt.lock")));
            assertArrayEquals(lockBeforeOfflineResolve, Files.readAllBytes(projectDirectory.resolve("zolt.lock")));

            CommandResult firstBuild = buildOffline(projectDirectory, offlineCache);
            Path classFile = projectDirectory.resolve("target/classes/com/example/Main.class");
            Path moduleFile = kotlinModule(projectDirectory);

            assertEquals(0, firstBuild.exitCode(), firstBuild.stderr());
            assertTrue(firstBuild.stdout().contains("Compiled 1 main source files"), firstBuild.stdout());
            assertTrue(Files.isRegularFile(classFile));
            assertTrue(Files.isRegularFile(moduleFile));

            CommandResult warmBuild = buildOffline(projectDirectory, offlineCache);

            assertEquals(0, warmBuild.exitCode(), warmBuild.stderr());
            assertTrue(warmBuild.stdout().contains("Skipped main compilation; inputs are unchanged"), warmBuild.stdout());

            KotlinCompilerCliFixture.writeSource(projectDirectory, "run");
            CommandResult run = execute(
                    "run",
                    "--cwd", projectDirectory.toString(),
                    "--cache-root", offlineCache.toString());

            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("real-kotlin-run"), run.stdout());
            assertTrue(run.stdout().contains("Ran com.example.Main"), run.stdout());

            byte[] classBeforeFailure = Files.readAllBytes(classFile);
            byte[] moduleBeforeFailure = Files.readAllBytes(moduleFile);
            Files.delete(KotlinCompilerCliFixture.compilerJar(offlineCache));
            KotlinCompilerCliFixture.writeSource(projectDirectory, "must-not-compile");

            CommandResult missingCompiler = buildOffline(projectDirectory, offlineCache);

            assertEquals(1, missingCompiler.exitCode());
            assertTrue(
                    missingCompiler.stderr().contains("Offline mode found corrupt cached JAR"),
                    missingCompiler.stderr());
            assertTrue(missingCompiler.stderr().contains(
                    "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + KotlinCompilerCliFixture.KOTLIN_VERSION),
                    missingCompiler.stderr());
            assertFalse(missingCompiler.stderr().contains("\tat "), missingCompiler.stderr());
            assertArrayEquals(classBeforeFailure, Files.readAllBytes(classFile));
            assertArrayEquals(moduleBeforeFailure, Files.readAllBytes(moduleFile));
            assertEquals(Map.of(), repository.authorizations(), "offline commands must not contact the repository");
        }
    }

    private static CommandResult buildOffline(Path projectDirectory, Path cacheRoot) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--cwd", projectDirectory.toString(),
                "--cache-root", cacheRoot.toString());
    }

    private static Path kotlinModule(Path projectDirectory) throws IOException {
        Path metadataDirectory = projectDirectory.resolve("target/classes/META-INF");
        try (Stream<Path> paths = Files.list(metadataDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin module metadata was not compiled"));
        }
    }
}
