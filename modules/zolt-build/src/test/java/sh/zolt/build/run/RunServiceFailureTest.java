package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.RunException;
import sh.zolt.doctor.CachingJdkChecker;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RunServiceFailureTest extends RunServiceTestSupport {
    @Test
    void enabledFrameworkRunRequiresRunnerResult() throws IOException {
        Path projectDir = tempDir.resolve("demo-missing-runner");
        Path cacheRoot = tempDir.resolve("cache-missing-runner");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        ProjectConfig config = config(true, Optional.empty());
        RunService service = service(
                frameworkRunAugmenter(true, Optional.empty(), projectDir, config, cacheRoot),
                (actualCommand, outputConsumer) -> {
                    throw new AssertionError("application JVM should not be launched without a framework runner");
                });

        RunException exception = assertThrows(
                RunException.class,
                () -> service.run(projectDir, config, cacheRoot, List.of()));

        assertTrue(exception.getMessage().contains(
                "Framework run augmenter was not configured for the enabled framework."));
    }

    @Test
    void failsBeforeLaunchingApplicationWhenCachedRuntimeJarDoesNotMatchLockfileHash() throws IOException {
        Path projectDir = tempDir.resolve("demo-corrupted-cache");
        Path cacheRoot = tempDir.resolve("cache-corrupted");
        Files.createDirectories(projectDir);
        Path runtimeJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "corrupted runtime jar bytes");
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                jarSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                dependencies = []
                """);
        ProjectConfig config = config(false, Optional.of("com.example.Main"));
        RunService service = service(
                (actualProjectDirectory, actualConfig, actualCacheRoot) -> Optional.empty(),
                (actualCommand, outputConsumer) -> {
                    throw new AssertionError("application JVM should not be launched with a corrupted cached jar");
                });

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> service.run(projectDir, config, cacheRoot, List.of()));

        assertTrue(exception.getMessage().contains(
                "Cached jar integrity check failed for com.example:runtime-lib:1.0.0"));
    }

    @Test
    void sharesCachedJdkDetectionAcrossBuildAndLaunch() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        ProjectConfig config = config(false, Optional.of("com.example.Main"));
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        RunService service = service(
                (actualProjectDirectory, actualConfig, actualCacheRoot) -> Optional.empty(),
                (actualCommand, outputConsumer) -> new JavaRunner.ProcessResult(0, "hello\n"),
                jdkChecker);

        service.run(projectDir, config, cacheRoot, List.of());

        assertEquals(2, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }
}
