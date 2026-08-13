package sh.zolt.lockfile;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;
import java.util.Optional;

/**
 * A single locked package entry. {@code toolGroups} is additive and specific to {@code tool-exec}
 * entries: a jar may serve several exec tools (its {@code toolGroups} union), while two tools needing
 * different versions of the same {@code groupId:artifactId} stay separate entries because their
 * {@code version} differs. Empty {@code toolGroups} on a {@code tool-exec} entry means the lock predates
 * per-tool isolation and must be re-resolved before its classpath can be trusted.
 */
public record LockPackage(
        PackageId packageId,
        String version,
        String source,
        DependencyScope scope,
        boolean direct,
        Optional<String> jar,
        Optional<String> pom,
        Optional<String> jarSha256,
        Optional<String> pomSha256,
        Optional<String> artifact,
        Optional<String> artifactType,
        Optional<String> artifactSha256,
        Optional<String> workspace,
        Optional<String> workspaceOutput,
        List<String> dependencies,
        List<String> members,
        List<String> exportedBy,
        List<String> policies,
        List<String> toolGroups) {
    public LockPackage {
        jar = cachePath("jar", jar);
        pom = cachePath("pom", pom);
        jarSha256 = jarSha256 == null ? Optional.empty() : jarSha256;
        pomSha256 = pomSha256 == null ? Optional.empty() : pomSha256;
        artifact = cachePath("artifact", artifact);
        artifactType = artifactType == null ? Optional.empty() : artifactType;
        artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
        workspace = workspace == null ? Optional.empty() : workspace;
        workspaceOutput = workspaceOutput == null ? Optional.empty() : workspaceOutput;
        dependencies = List.copyOf(dependencies);
        members = members == null ? List.of() : List.copyOf(members);
        exportedBy = exportedBy == null ? List.of() : List.copyOf(exportedBy);
        policies = policies == null ? List.of() : List.copyOf(policies);
        toolGroups = toolGroups == null ? List.of() : List.copyOf(toolGroups);
    }

    private static Optional<String> cachePath(String field, Optional<String> path) {
        Optional<String> candidate = path == null ? Optional.empty() : path;
        try {
            return candidate.map(value -> new CacheRelativePath(value).value());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid lockfile package `" + field + "` path: " + exception.getMessage(),
                    exception);
        }
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            List<String> dependencies) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> artifact,
            Optional<String> artifactType,
            Optional<String> artifactSha256,
            Optional<String> workspace,
            Optional<String> workspaceOutput,
            List<String> dependencies,
            List<String> members,
            List<String> exportedBy) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                artifact,
                artifactType,
                artifactSha256,
                workspace,
                workspaceOutput,
                dependencies,
                members,
                exportedBy,
                List.of(),
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> artifact,
            Optional<String> artifactType,
            Optional<String> artifactSha256,
            List<String> dependencies,
            List<String> policies) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                artifact,
                artifactType,
                artifactSha256,
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(),
                List.of(),
                policies,
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> artifact,
            Optional<String> artifactType,
            Optional<String> artifactSha256,
            List<String> dependencies) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                artifact,
                artifactType,
                artifactSha256,
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> workspace,
            Optional<String> workspaceOutput,
            List<String> dependencies) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                workspace,
                workspaceOutput,
                dependencies,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> workspace,
            Optional<String> workspaceOutput,
            List<String> dependencies,
            List<String> members) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                workspace,
                workspaceOutput,
                dependencies,
                members,
                List.of(),
                List.of(),
                List.of());
    }

    public LockPackage(
            PackageId packageId,
            String version,
            String source,
            DependencyScope scope,
            boolean direct,
            Optional<String> jar,
            Optional<String> pom,
            Optional<String> jarSha256,
            Optional<String> pomSha256,
            Optional<String> workspace,
            Optional<String> workspaceOutput,
            List<String> dependencies,
            List<String> members,
            List<String> exportedBy) {
        this(
                packageId,
                version,
                source,
                scope,
                direct,
                jar,
                pom,
                jarSha256,
                pomSha256,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                workspace,
                workspaceOutput,
                dependencies,
                members,
                exportedBy,
                List.of(),
                List.of());
    }
}
