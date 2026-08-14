package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.RunPackageException;
import sh.zolt.doctor.CachingJdkChecker;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RunPackageServiceFailureTest extends RunPackageServiceTestSupport {
    @Test
    void failsBeforeLaunchingPackageWhenCachedRuntimeJarDoesNotMatchLockfileHash() throws IOException {
        Path cacheRoot = projectDir.resolve("cache-corrupted");
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
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                jarSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                dependencies = []
                """);
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        RunPackageService service = service((command, outputConsumer) -> {
            throw new AssertionError("packaged application JVM should not be launched with a corrupted cached jar");
        });

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> service.runPackage(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        cacheRoot,
                        List.of()));

        assertTrue(exception.getMessage().contains(
                "Cached jar integrity check failed for com.example:runtime-lib:1.0.0"));
    }

    @Test
    void plainWarPackageFailsWithDeploymentGuidance() {
        RunPackageService service = service((command, outputConsumer) -> new JavaRunner.ProcessResult(0, ""));

        RunPackageException exception = assertThrows(
                RunPackageException.class,
                () -> service.runPackage(
                        projectDir,
                        config(Optional.empty())
                                .withPackageSettings(new PackageSettings(PackageMode.WAR)),
                        projectDir.resolve("cache"),
                        List.of()));

        assertTrue(exception.getMessage().contains("cannot be run directly"));
        assertTrue(exception.getMessage().contains("servlet container"));
        assertTrue(exception.getMessage().contains("spring-boot-war"));
    }

    @Test
    void missingMainClassProducesActionableErrorBeforePackaging() {
        RunPackageService service = service((command, outputConsumer) -> new JavaRunner.ProcessResult(0, ""));

        RunPackageException exception = assertThrows(
                RunPackageException.class,
                () -> service.runPackage(
                        projectDir,
                        config(Optional.empty()),
                        projectDir.resolve("cache"),
                        List.of()));

        assertTrue(exception.getMessage().contains("No main class is configured"));
        assertTrue(exception.getMessage().contains("[project].main"));
    }

    @Test
    void sharesCachedJdkDetectionAcrossBuildPackageAndLaunch() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        RunPackageService service = service(
                (command, outputConsumer) -> new JavaRunner.ProcessResult(0, "hello\n"),
                jdkChecker);

        service.runPackage(
                projectDir,
                config(Optional.of("com.example.Main")),
                cacheRoot,
                List.of());

        assertEquals(2, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }
}
