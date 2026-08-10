package sh.zolt.build.nativeimage;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;

final class NativePackagePolicy {
    private NativePackagePolicy() {
    }

    static ProjectConfig packageConfig(ProjectConfig config) {
        return config.packageSettings().mode() == PackageMode.UBER
                ? config
                : config.withPackageSettings(PackageSettings.defaults());
    }

    static List<Path> runtimeClasspath(PackageResult packageResult, List<Path> runtimeClasspath) {
        if (packageResult.mode() == PackageMode.UBER || runtimeClasspath == null) {
            return List.of();
        }
        return runtimeClasspath;
    }
}
