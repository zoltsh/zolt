package sh.zolt.build.packageplan;

import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PackagePlanOutputs {
    private PackagePlanOutputs() {
    }

    static List<PackagePlanOutput> forConfig(
            Path projectRoot,
            ProjectConfig config,
            Path archivePath,
            Optional<Path> runtimeClasspathPath) {
        List<PackagePlanOutput> outputs = new ArrayList<>();
        outputs.add(new PackagePlanOutput("main", archivePath));
        runtimeClasspathPath.ifPresent(
                path -> outputs.add(new PackagePlanOutput("runtime-classpath", path)));
        if (config.packageSettings().mode() != PackageMode.BOM) {
            if (config.packageSettings().sources()) {
                outputs.add(new PackagePlanOutput(
                        "sources",
                        classifierJarPath(projectRoot, config, "sources")));
            }
            if (config.packageSettings().javadoc()) {
                outputs.add(new PackagePlanOutput(
                        "javadoc",
                        classifierJarPath(projectRoot, config, "javadoc")));
            }
            if (config.packageSettings().tests()) {
                outputs.add(new PackagePlanOutput(
                        "tests",
                        classifierJarPath(projectRoot, config, "tests")));
            }
        }
        return List.copyOf(outputs);
    }

    public static Path classifierJarPath(
            Path projectRoot,
            ProjectConfig config,
            String classifier) {
        return ProjectPaths.output(
                projectRoot,
                "package artifact",
                config.build().outputRoot()
                        + "/"
                        + artifactBaseName(config)
                        + "-"
                        + classifier
                        + ".jar");
    }

    private static String artifactBaseName(ProjectConfig config) {
        return ProjectPaths.filenameComponent(
                        "[project].name",
                        config.project().name())
                + "-"
                + ProjectPaths.filenameComponent(
                        "[project].version",
                        config.project().version());
    }
}
