package sh.zolt.tree;

import sh.zolt.project.PackageMode;
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
                artifactType(config.packageSettings().mode()));
    }

    private static String artifactType(PackageMode mode) {
        return switch (mode) {
            case WAR, SPRING_BOOT_WAR -> "war";
            case BOM -> "pom";
            case THIN, SPRING_BOOT, QUARKUS, UBER -> "jar";
        };
    }
}
