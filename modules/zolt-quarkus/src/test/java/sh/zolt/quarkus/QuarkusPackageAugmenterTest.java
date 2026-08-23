package sh.zolt.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.bootstrap.QuarkusBootstrapWorkerResult;
import sh.zolt.quarkus.production.QuarkusAugmentationResult;
import sh.zolt.quarkus.production.QuarkusBuildAugmentationService;
import sh.zolt.quarkus.production.QuarkusBuildAugmentationServiceTestSupport;
import sh.zolt.lockfile.ProjectBuildContext;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class QuarkusPackageAugmenterTest extends QuarkusBuildAugmentationServiceTestSupport {
    @Test
    void derivesApplicationLayoutFromConfiguredOutputRoot() {
        ProjectConfig config = config(true)
                .withBuildSettings(new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        ".zolt/build",
                        ".zolt/build/classes",
                        ".zolt/build/test-classes"));
        Path packageDirectory = Path.of("/repo/.zolt/build/quarkus-app");
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar");
        QuarkusAugmentationResult augmentationResult = augmentationResult(packageDirectory, runnerJar);
        QuarkusPackageAugmenter augmenter = new QuarkusPackageAugmenter(new QuarkusBuildAugmentationService(
                (projectDirectory, actualConfig, cacheRoot) -> plan(),
                plan -> request(),
                (actualConfig, request) -> augmentationResult));

        Optional<FrameworkPackageResult> result = augmenter.augmentIfEnabled(
                ProjectBuildContext.standalone(Path.of("/repo")),
                config,
                Path.of("/cache"));

        assertTrue(result.isPresent());
        FrameworkPackageResult packageResult = result.orElseThrow();
        assertEquals(PackageMode.QUARKUS, packageResult.mode());
        assertEquals(packageDirectory, packageResult.packageDirectory());
        assertEquals(runnerJar, packageResult.runnerJar());
        assertEquals(".zolt/build/quarkus-app/app", packageResult.applicationLayout());
    }

    @Test
    void inspectPackageDirectoryDiagnosticDoesNotMentionFixedTargetLayout() {
        QuarkusPackageAugmenter augmenter = new QuarkusPackageAugmenter();

        assertEquals(
                "Could not inspect Quarkus package directory at /repo/.zolt/build/quarkus-app. "
                        + "Check that the Quarkus package directory is readable and retry.",
                augmenter.inspectPackageDirectoryMessage(
                        PackageMode.QUARKUS,
                        Path.of("/repo/.zolt/build/quarkus-app")));
    }

    private static QuarkusAugmentationResult augmentationResult(Path packageDirectory, Path runnerJar) {
        QuarkusBootstrapWorkerResult workerResult = new QuarkusBootstrapWorkerResult(
                fingerprint(),
                packageDirectory,
                runnerJar,
                packageDirectory.resolve("lib"),
                1);
        return new QuarkusAugmentationResult(
                packageDirectory.getParent().resolve("quarkus"),
                packageDirectory.getParent().resolve("quarkus/zolt-augmentation.properties"),
                descriptor(request()),
                fingerprint(),
                workerResult);
    }
}
