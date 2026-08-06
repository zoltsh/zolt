package sh.zolt.sbom;

import java.util.List;
import sh.zolt.project.ProjectConfig;

/**
 * A workspace member for SBOM aggregation: its declared path (matching the lockfile {@code members}
 * attribution), its {@link ProjectConfig}, and its canonical locked graph roots.
 */
public record SbomWorkspaceMember(
        String path,
        ProjectConfig config,
        List<String> dependencies) {

    public SbomWorkspaceMember {
        dependencies = dependencies.stream().sorted().distinct().toList();
    }
}
