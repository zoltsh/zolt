package sh.zolt.tree;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;

/**
 * A two-member workspace lock exercising the identities the schema-2 contract must keep separate:
 * a first-party member package, one coordinate present in two scopes, a classified jar, a non-default
 * artifact type, and an external shared by both members whose child set differs per member.
 */
abstract class WorkspaceTreeTestSupport {
    protected static final String WORKSPACE_NAME = "demo-workspace";
    protected static final List<String> MEMBERS = List.of("modules/core", "apps/api");

    protected static ZoltLockfile workspaceLockfile() {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(
                        memberPackage(),
                        sharedCompile(),
                        sharedTest(),
                        extra(),
                        classifiedAgent(),
                        typedBundle()),
                List.of(),
                List.of(),
                List.of(
                        sharedGraph("apps/api", List.of("org.example:extra:2.0.0:jar:compile")),
                        sharedGraph("modules/core", List.of())));
    }

    /** The first-party member `modules/core` produces, as the root lock records it. */
    protected static LockPackage memberPackage() {
        return new LockPackage(
                new PackageId("com.example", "core"),
                "0.1.0",
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("modules/core"),
                Optional.of("target/classes"),
                List.of("org.example:shared:1.0.0:jar:compile"),
                List.of("apps/api"));
    }

    protected static LockPackage sharedCompile() {
        return external(
                "org.example",
                "shared",
                "1.0.0",
                DependencyScope.COMPILE,
                true,
                List.of("org.example:extra:2.0.0:jar:compile"),
                List.of("modules/core", "apps/api"));
    }

    /** The same artifact bytes in a second scope: a separate occurrence that must not be merged. */
    protected static LockPackage sharedTest() {
        return external(
                "org.example",
                "shared",
                "1.0.0",
                DependencyScope.TEST,
                true,
                List.of(),
                List.of("modules/core"));
    }

    protected static LockPackage extra() {
        return external(
                "org.example",
                "extra",
                "2.0.0",
                DependencyScope.COMPILE,
                false,
                List.of(),
                List.of("apps/api"));
    }

    /** A `runtime`-classified jar, whose variant key is `jar|runtime`. */
    protected static LockPackage classifiedAgent() {
        return new LockPackage(
                new PackageId("org.example", "agent"),
                "0.9.0",
                "test",
                DependencyScope.TOOL_COVERAGE,
                false,
                Optional.of("org/example/agent/0.9.0/agent-0.9.0-runtime.jar"),
                Optional.of("org/example/agent/0.9.0/agent-0.9.0.pom"),
                Optional.of("jar-sha-agent"),
                Optional.of("pom-sha-agent"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("modules/core", "apps/api"),
                List.of());
    }

    /** A non-default artifact type, whose variant key is the bare extension `zip`. */
    protected static LockPackage typedBundle() {
        return new LockPackage(
                new PackageId("org.example", "bundle"),
                "3.0.0",
                "test",
                DependencyScope.RUNTIME,
                true,
                Optional.empty(),
                Optional.of("org/example/bundle/3.0.0/bundle-3.0.0.pom"),
                Optional.empty(),
                Optional.of("pom-sha-bundle"),
                Optional.of("org/example/bundle/3.0.0/bundle-3.0.0.zip"),
                Optional.of("zip"),
                Optional.of("artifact-sha-bundle"),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("apps/api"),
                List.of());
    }

    protected static LockMemberGraph sharedGraph(String member, List<String> dependencies) {
        return new LockMemberGraph(
                member,
                new PackageId("org.example", "shared"),
                "1.0.0",
                LockArtifactVariant.defaultVariant(),
                DependencyScope.COMPILE,
                dependencies,
                List.of());
    }

    protected static LockPackage external(
            String groupId,
            String artifactId,
            String version,
            DependencyScope scope,
            boolean direct,
            List<String> dependencies,
            List<String> members) {
        String base = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "test",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-sha-" + artifactId),
                Optional.of("pom-sha-" + artifactId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                members,
                List.of());
    }
}
