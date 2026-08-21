package sh.zolt.cli.command;

import sh.zolt.build.BuildService;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.nativeimage.NativeBuildService;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.run.RunPackageService;
import sh.zolt.build.run.RunService;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.framework.FrameworkBuildAugmenter;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.coverage.WorkspaceCoverageService;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildService;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.packaging.WorkspaceRunPackageService;
import sh.zolt.workspace.run.WorkspaceRunService;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.util.Objects;

public final class CommandServiceBundles {
    private CommandServiceBundles() {
    }

    @FunctionalInterface
    public interface CoverageServiceFactory {
        /** Creates a coverage service that instruments tests run with {@code runChecker} and reports with {@code compileChecker}. */
        CoverageService create(JdkChecker compileChecker, JdkChecker runChecker);
    }

    public record CommandCoverageServices(
            ManifestProjectLoader projectLoader,
            ResolveService resolveService,
            CoverageService coverageService,
            WorkspaceCoverageService workspaceCoverageService,
            CoverageServiceFactory coverageServiceFactory,
            TestRunServiceFactory testRunServiceFactory) {
        public CommandCoverageServices {
            Objects.requireNonNull(projectLoader, "projectLoader");
            Objects.requireNonNull(resolveService, "resolveService");
            Objects.requireNonNull(coverageService, "coverageService");
            Objects.requireNonNull(workspaceCoverageService, "workspaceCoverageService");
            Objects.requireNonNull(coverageServiceFactory, "coverageServiceFactory");
            Objects.requireNonNull(testRunServiceFactory, "testRunServiceFactory");
        }
    }

    public record CommandNativeServices(
            ManifestProjectLoader projectLoader,
            NativeBuildService nativeBuildService,
            WorkspaceNativeBuildService workspaceNativeBuildService) {
        public CommandNativeServices {
            Objects.requireNonNull(projectLoader, "projectLoader");
            Objects.requireNonNull(nativeBuildService, "nativeBuildService");
            Objects.requireNonNull(workspaceNativeBuildService, "workspaceNativeBuildService");
        }
    }

    public record CommandVersionAliasServices(
            CommandServiceClusters.CommandConfigEditServices configEditServices) {
        public CommandVersionAliasServices {
            Objects.requireNonNull(configEditServices, "configEditServices");
        }

        public ZoltTomlParser tomlParser() {
            return configEditServices.tomlParser();
        }

        public ZoltTomlWriter tomlWriter() {
            return configEditServices.tomlWriter();
        }

        public ResolveService resolveService() {
            return configEditServices.resolveService();
        }
    }

    public record CommandDependencyEditServices(
            CoordinateParser coordinateParser,
            CommandServiceClusters.CommandConfigEditServices configEditServices) {
        public CommandDependencyEditServices {
            Objects.requireNonNull(coordinateParser, "coordinateParser");
            Objects.requireNonNull(configEditServices, "configEditServices");
        }

        public ZoltTomlParser tomlParser() {
            return configEditServices.tomlParser();
        }

        public ZoltTomlWriter tomlWriter() {
            return configEditServices.tomlWriter();
        }

        public ResolveService resolveService() {
            return configEditServices.resolveService();
        }
    }

    public record CommandResolveServices(
            ResolveService resolveService,
            WorkspaceResolveService workspaceResolveService) {
        public CommandResolveServices {
            Objects.requireNonNull(resolveService, "resolveService");
            Objects.requireNonNull(workspaceResolveService, "workspaceResolveService");
        }
    }

    public record CommandBuildServices(
            BuildService buildService,
            WorkspaceBuildService workspaceBuildService,
            FrameworkBuildAugmenter frameworkBuildAugmenter) {
        public CommandBuildServices {
            Objects.requireNonNull(buildService, "buildService");
            Objects.requireNonNull(workspaceBuildService, "workspaceBuildService");
            Objects.requireNonNull(frameworkBuildAugmenter, "frameworkBuildAugmenter");
        }
    }

    public record CommandPackageServices(
            PackagePlanService packagePlanService,
            PackageService packageService,
            BuildService buildService,
            WorkspacePackageService workspacePackageService) {
        public CommandPackageServices {
            Objects.requireNonNull(packagePlanService, "packagePlanService");
            Objects.requireNonNull(packageService, "packageService");
            Objects.requireNonNull(buildService, "buildService");
            Objects.requireNonNull(workspacePackageService, "workspacePackageService");
        }
    }

    public record CommandQualityServices(
            QualityCheckService qualityCheckService) {
        public CommandQualityServices {
            Objects.requireNonNull(qualityCheckService, "qualityCheckService");
        }
    }

    public record CommandPublishServices(
            PublishDryRunService publishDryRunService,
            WorkspacePublishService workspacePublishService) {
        public CommandPublishServices {
            Objects.requireNonNull(publishDryRunService, "publishDryRunService");
            Objects.requireNonNull(workspacePublishService, "workspacePublishService");
        }
    }

    public record CommandRunPackageServices(
            RunPackageService runPackageService,
            WorkspaceRunPackageService workspaceRunPackageService) {
        public CommandRunPackageServices {
            Objects.requireNonNull(runPackageService, "runPackageService");
            Objects.requireNonNull(workspaceRunPackageService, "workspaceRunPackageService");
        }
    }

    public record CommandRunServices(
            RunService runService,
            WorkspaceRunService workspaceRunService) {
        public CommandRunServices {
            Objects.requireNonNull(runService, "runService");
            Objects.requireNonNull(workspaceRunService, "workspaceRunService");
        }
    }

    @FunctionalInterface
    public interface TestRunServiceFactory {
        /** Creates a service that compiles with {@code compileChecker} and runs tests with {@code runChecker}. */
        TestRunService create(JdkChecker compileChecker, JdkChecker runChecker);

        /** Creates a service that compiles and runs tests with the same toolchain checker. */
        default TestRunService create(JdkChecker jdkChecker) {
            return create(jdkChecker, jdkChecker);
        }
    }

    public record CommandTestServices(
            TestRunService testRunService,
            WorkspaceTestService workspaceTestService,
            TestRunServiceFactory testRunServiceFactory) {
        public CommandTestServices {
            Objects.requireNonNull(testRunService, "testRunService");
            Objects.requireNonNull(workspaceTestService, "workspaceTestService");
            Objects.requireNonNull(testRunServiceFactory, "testRunServiceFactory");
        }
    }
}
