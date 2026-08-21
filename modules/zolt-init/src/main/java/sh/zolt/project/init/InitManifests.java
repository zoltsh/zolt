package sh.zolt.project.init;

import java.util.List;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/**
 * The authored manifests {@code zolt init} emits.
 *
 * <p>Every value the language already defaults is left out: no {@code [repositories]} entry for
 * Maven Central, no conventional build paths, no {@code jar} package mode, and no false flags
 * (design §5.1). A workspace member inherits {@code group}, {@code version}, and {@code java} from
 * {@code [workspace.project]} and never materializes them (design §4.3).
 */
final class InitManifests {
    private static final String INITIAL_VERSION = "0.1.0";
    private static final String TEST_FRAMEWORK = "org.junit.jupiter:junit-jupiter";
    private static final String TEST_FRAMEWORK_VERSION = "5.14.4";

    private InitManifests() {
    }

    /** A complete standalone project: identity is authored in full (design §4.1). */
    static AuthoredManifest project(
            String name,
            String group,
            int javaRelease,
            String mainClass,
            boolean includeTests) {
        return manifest(
                Optional.empty(),
                Optional.of(new AuthoredProject(
                        new AuthoredProjectIdentity(
                                new ProjectName(name),
                                Optional.of(new ProjectVersion(INITIAL_VERSION)),
                                Optional.of(new ProjectGroup(group)),
                                Optional.of(new JavaFeatureRelease(javaRelease)),
                                Optional.empty()),
                        metadata(mainClass))),
                testDependencies(includeTests));
    }

    /** A workspace member: only the name is authored, the rest inherits (design §4.3). */
    static AuthoredManifest member(String name, String mainClass, boolean includeTests) {
        return manifest(
                Optional.empty(),
                Optional.of(new AuthoredProject(
                        new AuthoredProjectIdentity(
                                new ProjectName(name),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()),
                        metadata(mainClass))),
                testDependencies(includeTests));
    }

    /**
     * A virtual workspace root. {@code default} names the one member this invocation created unless
     * all-members selection was requested, which is the only implicit-all form (design §6.2).
     */
    static AuthoredManifest workspaceRoot(
            String name,
            String group,
            int javaRelease,
            String memberPath,
            boolean allMembers) {
        return manifest(
                Optional.of(new AuthoredWorkspace(
                        new LocalId(name),
                        new AuthoredWorkspaceMembers(
                                List.of(new WorkspaceMemberPattern(memberPath)),
                                List.of(),
                                allMembers
                                        ? Optional.empty()
                                        : Optional.of(List.of(new WorkspaceMemberPath(memberPath)))),
                        Optional.of(new AuthoredWorkspaceProjectDefaults(
                                Optional.of(new ProjectGroup(group)),
                                Optional.of(new ProjectVersion(INITIAL_VERSION)),
                                Optional.of(new JavaFeatureRelease(javaRelease)),
                                Optional.empty())))),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredManifest manifest(
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project,
            Optional<AuthoredDependencies> dependencies) {
        return new AuthoredManifest(
                workspace,
                project,
                AuthoredToolchains.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                AuthoredPackaging.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredProjectMetadata metadata(String mainClass) {
        return new AuthoredProjectMetadata(
                Optional.of(new JavaBinaryClassName(mainClass)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                java.util.Map.of());
    }

    private static Optional<AuthoredDependencies> testDependencies(boolean includeTests) {
        if (!includeTests) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredDependencies(List.of(new AuthoredDependency(
                DependencyLane.TEST,
                new DependencyCoordinate(TEST_FRAMEWORK),
                new DependencySelector.FixedVersion(TEST_FRAMEWORK_VERSION),
                AuthoredDependencyMetadata.none()))));
    }
}
