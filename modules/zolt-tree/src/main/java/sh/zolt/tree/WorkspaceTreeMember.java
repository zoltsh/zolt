package sh.zolt.tree;

import sh.zolt.project.ProjectConfig;

/** A workspace member's path and canonical Maven artifact identity for the tree machine contract. */
public record WorkspaceTreeMember(
        String path,
        String group,
        String name,
        String version,
        String type) {

    public static WorkspaceTreeMember from(String path, ProjectConfig config) {
        return new WorkspaceTreeMember(
                path,
                config.project().group(),
                config.project().name(),
                config.project().version(),
                config.packageSettings().mode().artifactType());
    }
}
