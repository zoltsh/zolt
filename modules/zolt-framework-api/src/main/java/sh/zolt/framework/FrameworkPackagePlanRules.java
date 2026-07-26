package sh.zolt.framework;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;

public interface FrameworkPackagePlanRules {
    default String evidenceIdentity() {
        return getClass().getName();
    }

    boolean supports(PackageMode mode);

    FrameworkPackagePlanDependency dependency(LockPackage lockPackage, ProjectConfig config);

    Path archivePath(Path projectRoot, ProjectConfig config);

    String applicationLayout(ProjectConfig config);
}
