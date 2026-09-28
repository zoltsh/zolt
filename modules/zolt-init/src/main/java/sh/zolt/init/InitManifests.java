package sh.zolt.init;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredKotlinToolchain;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

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
    private static final String KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";
    private static final String KOTLIN_VERSION = "2.2.0";

    private InitManifests() {
    }

    /** A complete standalone project: identity is authored in full (design §4.1). */
    static AuthoredManifest project(
            String name,
            String group,
            int javaRelease,
            String mainClass,
            boolean includeTests,
            ProjectInitLanguage language) {
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
                toolchains(language),
                dependencies(language, includeTests),
                build(language, includeTests));
    }

    /** A workspace member: only the name is authored, the rest inherits (design §4.3). */
    static AuthoredManifest member(
            String name,
            String mainClass,
            boolean includeTests,
            ProjectInitLanguage language) {
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
                AuthoredToolchains.empty(),
                dependencies(language, includeTests),
                build(language, includeTests));
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
            boolean allMembers,
            ProjectInitLanguage language) {
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
                toolchains(language),
                Optional.empty(),
                AuthoredBuildConfiguration.empty());
    }

    private static AuthoredManifest manifest(
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project,
            AuthoredToolchains toolchains,
            Optional<AuthoredDependencies> dependencies,
            AuthoredBuildConfiguration build) {
        return new AuthoredManifest(
                workspace,
                project,
                toolchains,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                Optional.empty(),
                Optional.empty(),
                build,
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
                Map.of());
    }

    private static AuthoredToolchains toolchains(ProjectInitLanguage language) {
        return switch (language) {
            case JAVA -> AuthoredToolchains.empty();
            case KOTLIN -> new AuthoredToolchains(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new AuthoredKotlinToolchain(
                            new KotlinToolchainVersion(KOTLIN_VERSION))));
        };
    }

    private static Optional<AuthoredDependencies> dependencies(
            ProjectInitLanguage language, boolean includeTests) {
        List<AuthoredDependency> declarations = new ArrayList<>();
        if (language == ProjectInitLanguage.KOTLIN) {
            declarations.add(dependency(
                    DependencyLane.IMPLEMENTATION, KOTLIN_STDLIB, KOTLIN_VERSION));
        }
        if (includeTests) {
            declarations.add(dependency(
                    DependencyLane.TEST, TEST_FRAMEWORK, TEST_FRAMEWORK_VERSION));
        }
        return declarations.isEmpty()
                ? Optional.empty()
                : Optional.of(new AuthoredDependencies(declarations));
    }

    private static AuthoredDependency dependency(
            DependencyLane lane, String coordinate, String version) {
        return new AuthoredDependency(
                lane,
                new DependencyCoordinate(coordinate),
                new DependencySelector.FixedVersion(version),
                AuthoredDependencyMetadata.none());
    }

    private static AuthoredBuildConfiguration build(
            ProjectInitLanguage language, boolean includeTests) {
        if (language == ProjectInitLanguage.JAVA) {
            return AuthoredBuildConfiguration.empty();
        }
        Optional<AuthoredTests> tests = includeTests
                ? Optional.of(new AuthoredTests(
                        Optional.of(new AuthoredTests.Sources(
                                List.of(),
                                List.of(),
                                List.of(path("src/test/kotlin")))),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of()))
                : Optional.empty();
        return new AuthoredBuildConfiguration(
                Optional.of(new AuthoredBuild(
                        List.of(path("src/main/kotlin")),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                tests,
                Optional.empty());
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
