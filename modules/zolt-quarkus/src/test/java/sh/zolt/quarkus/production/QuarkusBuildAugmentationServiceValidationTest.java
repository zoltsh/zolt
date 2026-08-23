package sh.zolt.quarkus.production;

import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.quarkus.QuarkusAugmentationException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class QuarkusBuildAugmentationServiceValidationTest extends QuarkusBuildAugmentationServiceTestSupport {
    @Test
    void requiresBuildInputs() {
        QuarkusBuildAugmentationService service = new QuarkusBuildAugmentationService(
                (projectDirectory, config, cacheRoot) -> plan(),
                plan -> request(),
                (config, request) -> result(request));

        assertThrows(
                QuarkusAugmentationException.class,
                () -> service.augmentIfEnabled(null, config(true), Path.of("/cache")));
        assertThrows(
                QuarkusAugmentationException.class,
                () -> service.augmentIfEnabled(context(), null, Path.of("/cache")));
        assertThrows(
                QuarkusAugmentationException.class,
                () -> service.augmentIfEnabled(context(), config(true), null));
    }

    private static ProjectBuildContext context() {
        return ProjectBuildContext.standalone(Path.of("/repo"));
    }
}
