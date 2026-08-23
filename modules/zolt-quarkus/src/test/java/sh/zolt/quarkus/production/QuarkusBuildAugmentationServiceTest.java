package sh.zolt.quarkus.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.ProjectBuildContext;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class QuarkusBuildAugmentationServiceTest extends QuarkusBuildAugmentationServiceTestSupport {
    @Test
    void skipsAugmentationWhenQuarkusIsDisabled() {
        boolean[] planned = new boolean[] {false};
        boolean[] ran = new boolean[] {false};
        QuarkusBuildAugmentationService service = new QuarkusBuildAugmentationService(
                (projectDirectory, config, cacheRoot) -> {
                    planned[0] = true;
                    return plan();
                },
                plan -> request(),
                (config, request) -> {
                    ran[0] = true;
                    return result(request);
                });

        Optional<QuarkusAugmentationResult> result = service.augmentIfEnabled(
                ProjectBuildContext.standalone(Path.of("/repo")),
                config(false),
                Path.of("/cache"));

        assertTrue(result.isEmpty());
        assertFalse(planned[0]);
        assertFalse(ran[0]);
    }

    /**
     * The member shape, because it is the one the defect lived in: the augmenter plans against the
     * WORKSPACE root's lock while compiling a member's own directory (design §4.5).
     */
    @Test
    void runsAugmentationForQuarkusEnabledProject() {
        Path workspaceRoot = Path.of("/ws");
        Path projectDirectory = workspaceRoot.resolve("apps/api");
        ProjectBuildContext context = ProjectBuildContext.member(
                projectDirectory, workspaceRoot.resolve("zolt.lock"), "apps/api");
        Path cacheRoot = Path.of("/cache");
        var config = config(true);
        var plan = plan();
        var request = request();
        var expected = result(request);
        QuarkusBuildAugmentationService service = new QuarkusBuildAugmentationService(
                (actualContext, actualConfig, actualCacheRoot) -> {
                    assertEquals(projectDirectory, actualContext.projectRoot());
                    assertEquals(workspaceRoot.resolve("zolt.lock"), actualContext.lockfilePath());
                    assertEquals("apps/api", actualContext.memberPath());
                    assertSame(config, actualConfig);
                    assertEquals(cacheRoot, actualCacheRoot);
                    return plan;
                },
                actualPlan -> {
                    assertSame(plan, actualPlan);
                    return request;
                },
                (actualConfig, actualRequest) -> {
                    assertSame(config, actualConfig);
                    assertSame(request, actualRequest);
                    return expected;
                });

        Optional<QuarkusAugmentationResult> result = service.augmentIfEnabled(context, config, cacheRoot);

        assertTrue(result.isPresent());
        assertSame(expected, result.orElseThrow());
    }
}
