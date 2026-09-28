package sh.zolt.cli.command.testcmd;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandBuildCache;
import sh.zolt.workspace.test.WorkspaceTestService;

/** Resolves one build-output cache and applies it consistently across every {@code zolt test} route. */
final class CommandTestBuildCacheSupport {
    private final BuildCacheService buildCache;

    private CommandTestBuildCacheSupport(BuildCacheService buildCache) {
        this.buildCache = buildCache;
    }

    static CommandTestBuildCacheSupport create(boolean disabledByFlag) {
        return new CommandTestBuildCacheSupport(CommandBuildCache.service(disabledByFlag, false));
    }

    TestRunService applyTo(TestRunService testRunService) {
        return testRunService.withBuildCache(buildCache);
    }

    WorkspaceTestService applyTo(WorkspaceTestService workspaceTestService) {
        return workspaceTestService.withBuildCache(buildCache);
    }

    void surfaceWarnings(CommandHumanOutput output) {
        CommandBuildCache.surfaceWarnings(output, buildCache);
    }
}
