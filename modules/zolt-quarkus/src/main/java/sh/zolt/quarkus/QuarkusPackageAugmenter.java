package sh.zolt.quarkus;

import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.project.PackageMode;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.production.QuarkusBuildAugmentationService;
import java.nio.file.Path;
import java.util.Optional;

public final class QuarkusPackageAugmenter implements FrameworkPackageAugmenter {
    private final QuarkusBuildAugmentationService augmentationService;

    public QuarkusPackageAugmenter() {
        this(new QuarkusBuildAugmentationService());
    }

    QuarkusPackageAugmenter(QuarkusBuildAugmentationService augmentationService) {
        this.augmentationService = augmentationService;
    }

    @Override
    public Optional<FrameworkPackageResult> augmentIfEnabled(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot) {
        return augmentationService.augmentIfEnabled(context, config, cacheRoot)
                .map(result -> new FrameworkPackageResult(
                        PackageMode.QUARKUS,
                        result.workerResult().packageDirectory(),
                        result.workerResult().runnerJar(),
                        applicationLayout(config)));
    }

    @Override
    public String missingPackageResultMessage(PackageMode mode) {
        return "Quarkus package mode requires [package] mode = \"quarkus\" in zolt.toml. "
                + "Set the package mode, run `zolt resolve`, then retry `zolt package --mode quarkus`.";
    }

    @Override
    public String missingRunnerJarMessage(PackageMode mode, Path runnerJar) {
        return "Quarkus package mode expected a runner jar at "
                + runnerJar
                + ". Run `zolt build` and check the Quarkus augmentation output.";
    }

    @Override
    public String inspectPackageDirectoryMessage(PackageMode mode, Path packageDirectory) {
        return "Could not inspect Quarkus package directory at "
                + packageDirectory
                + ". Check that the Quarkus package directory is readable and retry.";
    }

    private static String applicationLayout(ProjectConfig config) {
        String outputRoot = config.build().outputRoot();
        String effectiveOutputRoot = outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
        return effectiveOutputRoot + "/quarkus-app/app";
    }
}
