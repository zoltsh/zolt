package sh.zolt.build.output;

import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Uses the resource copier's actual destination plan, including generated resource lanes. */
public final class CompilerOutputOverlays {
    private CompilerOutputOverlays() {}

    public static Set<Path> planned(Path project, ProjectConfig config, boolean test) {
        return planned(project, config, test, new sh.zolt.build.metadata.BuildMetadataGenerator());
    }

    public static Set<Path> planned(Path project, ProjectConfig config, boolean test,
            sh.zolt.build.metadata.BuildMetadataGenerator metadata) {
        Set<Path> paths = new HashSet<>(new ResourceCopier().plannedOutputPaths(project, config, test));
        if (!test) {
            paths.addAll(metadata.plannedOutputPaths(project, config));
        }
        return Set.copyOf(paths);
    }

    public static Set<Path> written(Path project, ProjectConfig config, java.util.List<Path> metadata) {
        Set<Path> paths = new HashSet<>(new ResourceCopier().plannedOutputPaths(project, config, false));
        Path output = project.resolve(config.build().output()).toAbsolutePath().normalize();
        metadata.forEach(path -> paths.add(output.relativize(path.toAbsolutePath().normalize())));
        return Set.copyOf(paths);
    }

    public static boolean removedCompilerOverlay(Path output, Set<Path> current) {
        return CompilerOutputInventory.read(output).map(previous -> previous.overlays().stream()
                .anyMatch(path -> previous.files().containsKey(path) && !current.contains(path))).orElse(false);
    }
}
