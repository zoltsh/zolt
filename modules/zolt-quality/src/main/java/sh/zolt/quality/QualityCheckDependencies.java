package sh.zolt.quality;

import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishSettingsReader;
import sh.zolt.quality.execution.QualityExecutionContextRunner;
import sh.zolt.quality.packaging.PackageQualityCheck;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.util.function.Function;

final class QualityCheckDependencies {
    private final GeneratedSourceQualityCheck generatedSourceQualityCheck;
    private final LockfileQualityCheck lockfileQualityCheck;
    private final QualityExecutionContextRunner executionContextRunner;
    private final PackageQualityCheck packageQualityCheck;
    private final DependencyQualityCheck dependencyQualityCheck;
    private final LicensePolicyQualityCheck licensePolicyQualityCheck;
    private final WorkspaceQualityProjectionService workspaceQualityProjectionService;

    private QualityCheckDependencies(
            GeneratedSourceQualityCheck generatedSourceQualityCheck,
            LockfileQualityCheck lockfileQualityCheck,
            QualityExecutionContextRunner executionContextRunner,
            PackageQualityCheck packageQualityCheck,
            DependencyQualityCheck dependencyQualityCheck,
            LicensePolicyQualityCheck licensePolicyQualityCheck,
            WorkspaceQualityProjectionService workspaceQualityProjectionService) {
        this.generatedSourceQualityCheck = generatedSourceQualityCheck;
        this.lockfileQualityCheck = lockfileQualityCheck;
        this.executionContextRunner = executionContextRunner;
        this.packageQualityCheck = packageQualityCheck;
        this.dependencyQualityCheck = dependencyQualityCheck;
        this.licensePolicyQualityCheck = licensePolicyQualityCheck;
        this.workspaceQualityProjectionService = workspaceQualityProjectionService;
    }

    static QualityCheckDependencies create(Function<String, String> environment) {
        ZoltLockfileReader lockfileReader = new ZoltLockfileReader();
        return new QualityCheckDependencies(
                new GeneratedSourceQualityCheck(new GeneratedSourceEvidenceService()),
                new LockfileQualityCheck(new ResolveService(), new WorkspaceResolveService(), lockfileReader),
                QualityExecutionContextRunner.create(
                        lockfileReader,
                        new PublishSettingsReader(),
                        environment,
                        new PublishDryRunService()),
                new PackageQualityCheck(new PackagePlanService(), new PackageEvidenceManifestReader()),
                new DependencyQualityCheck(lockfileReader, new DependencyPolicyReportService()),
                new LicensePolicyQualityCheck(lockfileReader),
                new WorkspaceQualityProjectionService(lockfileReader));
    }

    GeneratedSourceQualityCheck generatedSourceQualityCheck() {
        return generatedSourceQualityCheck;
    }

    LockfileQualityCheck lockfileQualityCheck() {
        return lockfileQualityCheck;
    }

    QualityExecutionContextRunner executionContextRunner() {
        return executionContextRunner;
    }

    PackageQualityCheck packageQualityCheck() {
        return packageQualityCheck;
    }

    DependencyQualityCheck dependencyQualityCheck() {
        return dependencyQualityCheck;
    }

    LicensePolicyQualityCheck licensePolicyQualityCheck() {
        return licensePolicyQualityCheck;
    }

    WorkspaceQualityProjectionService workspaceQualityProjectionService() {
        return workspaceQualityProjectionService;
    }
}
