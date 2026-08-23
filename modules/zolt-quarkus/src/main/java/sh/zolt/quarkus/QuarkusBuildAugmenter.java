package sh.zolt.quarkus;

import sh.zolt.framework.FrameworkBuildAugmentationResult;
import sh.zolt.framework.FrameworkBuildAugmenter;
import sh.zolt.framework.FrameworkBuildException;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.production.QuarkusBuildAugmentationService;
import java.nio.file.Path;
import java.util.Optional;

public final class QuarkusBuildAugmenter implements FrameworkBuildAugmenter {
    private final QuarkusBuildAugmentationService augmentationService;

    public QuarkusBuildAugmenter() {
        this(new QuarkusBuildAugmentationService());
    }

    QuarkusBuildAugmenter(QuarkusBuildAugmentationService augmentationService) {
        this.augmentationService = augmentationService;
    }

    @Override
    public Optional<FrameworkBuildAugmentationResult> augmentIfEnabled(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot) {
        try {
            return augmentationService.augmentIfEnabled(context, config, cacheRoot)
                    .map(result -> new FrameworkBuildAugmentationResult(
                            "Quarkus",
                            result.workerResult().runnerJar()));
        } catch (QuarkusAugmentationException | QuarkusPlanException exception) {
            throw new FrameworkBuildException(exception.getMessage(), exception);
        }
    }
}
