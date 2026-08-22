package sh.zolt.cli.command;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.framework.FrameworkBuildAugmenter;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.resolve.ResolveService;
import sh.zolt.cli.command.dependency.ManifestMutationServices;
import java.util.Objects;

public final class CommandServiceClusters {
    private CommandServiceClusters() {
    }

    public record CommandConfigEditServices(
            ManifestMutationServices manifests,
            ResolveService resolveService) {
        public CommandConfigEditServices {
            Objects.requireNonNull(manifests, "manifests");
            Objects.requireNonNull(resolveService, "resolveService");
        }
    }

    public record CommandPackageFrameworkServices(
            FrameworkPackageAugmenter packageAugmenter,
            PackagePlanService packagePlanService) {
        public CommandPackageFrameworkServices {
            Objects.requireNonNull(packageAugmenter, "packageAugmenter");
            Objects.requireNonNull(packagePlanService, "packagePlanService");
        }
    }

    public record CommandBuildFrameworkServices(
            FrameworkBuildAugmenter frameworkBuildAugmenter,
            ResolveService resolveService) {
        public CommandBuildFrameworkServices {
            Objects.requireNonNull(frameworkBuildAugmenter, "frameworkBuildAugmenter");
            Objects.requireNonNull(resolveService, "resolveService");
        }
    }

    public record CommandTestFrameworkServices(
            FrameworkTestRunner frameworkTestRunner,
            ResolveService resolveService) {
        public CommandTestFrameworkServices {
            Objects.requireNonNull(frameworkTestRunner, "frameworkTestRunner");
            Objects.requireNonNull(resolveService, "resolveService");
        }
    }

    public record CommandRunFrameworkServices(
            FrameworkRunAugmenter frameworkRunAugmenter,
            ResolveService resolveService) {
        public CommandRunFrameworkServices {
            Objects.requireNonNull(frameworkRunAugmenter, "frameworkRunAugmenter");
            Objects.requireNonNull(resolveService, "resolveService");
        }
    }
}
