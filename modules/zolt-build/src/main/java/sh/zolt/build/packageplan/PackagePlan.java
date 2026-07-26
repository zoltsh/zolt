package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import sh.zolt.project.PackageMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record PackagePlan(
        Path projectRoot,
        PackageMode mode,
        Path archivePath,
        Path applicationOutput,
        String applicationLayout,
        Optional<Path> runtimeClasspathPath,
        List<PackagePlanDependency> dependencies,
        List<PackagePlanWarning> warnings,
        PackagePlanEvidence evidence) {
    public PackagePlan {
        if (projectRoot == null) {
            throw new PackageException("Package plan requires a project root.");
        }
        mode = mode == null ? PackageMode.THIN : mode;
        if (archivePath == null) {
            throw new PackageException("Package plan requires an archive path.");
        }
        if (applicationOutput == null) {
            throw new PackageException("Package plan requires an application output path.");
        }
        if (applicationLayout == null || applicationLayout.isBlank()) {
            throw new PackageException("Package plan requires an application layout.");
        }
        runtimeClasspathPath = runtimeClasspathPath == null ? Optional.empty() : runtimeClasspathPath;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        evidence = evidence == null
                ? PackagePlanEvidence.unavailable(
                        mode,
                        archivePath,
                        runtimeClasspathPath)
                : evidence;
    }

    public PackagePlan(
            Path projectRoot,
            PackageMode mode,
            Path archivePath,
            Path applicationOutput,
            String applicationLayout,
            Optional<Path> runtimeClasspathPath,
            List<PackagePlanDependency> dependencies,
            List<PackagePlanWarning> warnings) {
        this(
                projectRoot,
                mode,
                archivePath,
                applicationOutput,
                applicationLayout,
                runtimeClasspathPath,
                dependencies,
                warnings,
                null);
    }
}
