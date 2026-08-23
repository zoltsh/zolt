package sh.zolt.quarkus;

import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.framework.FrameworkRunException;
import sh.zolt.framework.FrameworkRunResult;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.production.QuarkusBuildAugmentationService;
import java.nio.file.Path;
import java.util.Optional;

public final class QuarkusRunAugmenter implements FrameworkRunAugmenter {
    private final QuarkusBuildAugmentationService augmentationService;

    public QuarkusRunAugmenter() {
        this(new QuarkusBuildAugmentationService());
    }

    QuarkusRunAugmenter(QuarkusBuildAugmentationService augmentationService) {
        this.augmentationService = augmentationService;
    }

    @Override
    public boolean isEnabled(ProjectConfig config) {
        return config.frameworkSettings().quarkus().enabled();
    }

    @Override
    public Optional<FrameworkRunResult> augmentIfEnabled(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot) {
        try {
            return augmentationService.augmentIfEnabled(context, config, cacheRoot)
                    .map(result -> new FrameworkRunResult(
                            result.workerResult().runnerJar(),
                            "Quarkus runner " + result.workerResult().runnerJar()));
        } catch (QuarkusAugmentationException | QuarkusPlanException exception) {
            throw new FrameworkRunException(exception.getMessage(), exception);
        }
    }
}
