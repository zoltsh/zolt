package sh.zolt.tree;

import java.util.List;
import sh.zolt.project.ProjectConfig;

/** A workspace member's path, Maven artifact identity, and roots for the tree machine contract. */
public record WorkspaceTreeMember(
        String path,
        String group,
        String name,
        String version,
        String type,
        List<String> dependencies) {

    public WorkspaceTreeMember {
        dependencies = dependencies.stream().sorted().distinct().toList();
    }

    public static WorkspaceTreeMember from(
            String path,
            ProjectConfig config,
            List<String> dependencies) {
        return new WorkspaceTreeMember(
                path,
                config.project().group(),
                config.project().name(),
                config.project().version(),
                config.packageSettings().mode().artifactType(),
                dependencies);
    }
}
