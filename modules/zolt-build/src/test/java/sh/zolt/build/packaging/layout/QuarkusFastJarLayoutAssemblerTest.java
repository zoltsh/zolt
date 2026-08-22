package sh.zolt.build.packaging.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.project.PackageMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusFastJarLayoutAssemblerTest {
    private final QuarkusFastJarLayoutAssembler assembler = new QuarkusFastJarLayoutAssembler();

    @TempDir
    private Path projectDir;

    @Test
    void adaptsVerifiedFrameworkFastJarResult() throws IOException {
        Path packageDirectory = projectDir.resolve("target/quarkus-app");
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar");
        Files.createDirectories(packageDirectory.resolve("lib"));
        Files.writeString(runnerJar, "runner\n");
        Files.writeString(packageDirectory.resolve("lib/runtime.jar"), "runtime\n");
        BuildResult buildResult = new BuildResult(Optional.empty(), 1, 0, projectDir.resolve("target/classes"), "");

        PackageResult result = assembler.assemble(
                buildResult,
                PackageMode.QUARKUS,
                new FrameworkPackageResult(
                        PackageMode.QUARKUS,
                        packageDirectory,
                        runnerJar,
                        "target/quarkus-app"),
                "missing runner",
                "inspect package directory");

        assertSame(buildResult, result.buildResult());
        assertEquals(PackageMode.QUARKUS, result.mode());
        assertEquals(runnerJar, result.jarPath());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertEquals(2, result.entryCount());
        assertTrue(result.hasMainClass());
        assertEquals("target/quarkus-app", result.applicationLayout());
    }

    @Test
    void reportsMissingRunnerJarWithFrameworkDiagnostic() {
        Path packageDirectory = projectDir.resolve("target/quarkus-app");
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> assembler.assemble(
                        new BuildResult(Optional.empty(), 0, 0, projectDir.resolve("target/classes"), ""),
                        PackageMode.QUARKUS,
                        new FrameworkPackageResult(PackageMode.QUARKUS, packageDirectory, runnerJar),
                        "expected runner jar",
                        "inspect package directory"));

        assertEquals("expected runner jar", exception.getMessage());
    }

    @Test
    void reportsPackageDirectoryInspectionFailureWithFrameworkDiagnostic() throws IOException {
        Path runnerJar = projectDir.resolve("target/quarkus-run.jar");
        Files.createDirectories(runnerJar.getParent());
        Files.writeString(runnerJar, "runner\n");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> assembler.assemble(
                        new BuildResult(Optional.empty(), 0, 0, projectDir.resolve("target/classes"), ""),
                        PackageMode.QUARKUS,
                        new FrameworkPackageResult(
                                PackageMode.QUARKUS,
                                projectDir.resolve("missing-quarkus-app"),
                                runnerJar),
                        "expected runner jar",
                        "inspect package directory"));

        assertEquals("inspect package directory", exception.getMessage());
    }

    @Test
    void rejectsMismatchedFrameworkPackageMode() throws IOException {
        Path packageDirectory = projectDir.resolve("target/quarkus-app");
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar");
        Files.createDirectories(packageDirectory);
        Files.writeString(runnerJar, "runner\n");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> assembler.assemble(
                        new BuildResult(Optional.empty(), 0, 0, projectDir.resolve("target/classes"), ""),
                        PackageMode.QUARKUS,
                        new FrameworkPackageResult(PackageMode.THIN, packageDirectory, runnerJar),
                        "expected runner jar",
                        "inspect package directory"));

        assertEquals(
                "Framework package mode `quarkus` returned package mode `jar`. Check the framework package adapter.",
                exception.getMessage());
    }
}
