package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/**
 * Complete parser-independent authored manifest before workspace and effective composition.
 *
 * <p>An optional collection-shaped domain is present when at least one of its source tables was
 * authored, even when that table is an explicitly empty collection. Required effective identity,
 * root/member merging, alias resolution, and BOM member-set validation require workspace context
 * and are intentionally deferred.
 */
public record AuthoredManifest(
        Optional<AuthoredWorkspace> workspace,
        Optional<AuthoredProject> project,
        AuthoredToolchains toolchains,
        Optional<AuthoredVersionAliases> versions,
        Optional<AuthoredDependencyRepositories> repositories,
        Optional<AuthoredCredentials> credentials,
        Optional<AuthoredPlatforms> platforms,
        Optional<AuthoredDependencies> dependencies,
        Optional<AuthoredDependencyConstraints> dependencyConstraints,
        Optional<AuthoredDependencyPolicy> dependencyPolicy,
        AuthoredBuildConfiguration build,
        Optional<AuthoredGeneratedSources> generated,
        AuthoredPackaging packaging,
        Optional<AuthoredPublishing> publishing,
        Optional<AuthoredCommands> commands) {
    public AuthoredManifest {
        workspace = Objects.requireNonNull(workspace, "Authored workspace must not be null.");
        project = Objects.requireNonNull(project, "Authored project must not be null.");
        toolchains = Objects.requireNonNull(toolchains, "Authored toolchains must not be null.");
        versions = Objects.requireNonNull(versions, "Authored versions must not be null.");
        repositories = Objects.requireNonNull(
                repositories, "Authored dependency repositories must not be null.");
        credentials = Objects.requireNonNull(credentials, "Authored credentials must not be null.");
        platforms = Objects.requireNonNull(platforms, "Authored platforms must not be null.");
        dependencies = Objects.requireNonNull(dependencies, "Authored dependencies must not be null.");
        dependencyConstraints = Objects.requireNonNull(
                dependencyConstraints, "Authored dependency constraints must not be null.");
        dependencyPolicy = Objects.requireNonNull(
                dependencyPolicy, "Authored dependency policy must not be null.");
        build = Objects.requireNonNull(build, "Authored build configuration must not be null.");
        generated = Objects.requireNonNull(generated, "Authored generated sources must not be null.");
        packaging = Objects.requireNonNull(packaging, "Authored packaging must not be null.");
        publishing = Objects.requireNonNull(publishing, "Authored publishing must not be null.");
        commands = Objects.requireNonNull(commands, "Authored commands must not be null.");

        if (workspace.isEmpty() && project.isEmpty()) {
            throw new IllegalArgumentException(
                    "An authored manifest must contain a [workspace] and/or [project] domain.");
        }
        if (project.isEmpty()) {
            validateVirtualWorkspaceRoot(
                    dependencies,
                    dependencyConstraints,
                    dependencyPolicy,
                    build,
                    generated,
                    packaging,
                    publishing);
        }
        if (packaging.bom().isPresent()) {
            validateBomProject(
                    workspace,
                    project.orElseThrow(),
                    toolchains,
                    dependencies,
                    dependencyConstraints,
                    dependencyPolicy,
                    build,
                    generated);
        }
    }

    private static void validateVirtualWorkspaceRoot(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> dependencyConstraints,
            Optional<AuthoredDependencyPolicy> dependencyPolicy,
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated,
            AuthoredPackaging packaging,
            Optional<AuthoredPublishing> publishing) {
        rejectVirtualProjectDomain(dependencies.isPresent(), "dependencies");
        rejectVirtualProjectDomain(
                dependencyConstraints.isPresent(), "dependency constraints");
        rejectVirtualProjectDomain(dependencyPolicy.isPresent(), "dependency policy");
        rejectVirtualProjectDomain(build.build().isPresent(), "build layout");
        rejectVirtualProjectDomain(build.compiler().isPresent(), "compiler settings");
        rejectVirtualProjectDomain(build.resources().isPresent(), "resources");
        rejectVirtualProjectDomain(build.tests().isPresent(), "tests");
        rejectVirtualProjectDomain(generated.isPresent(), "generated sources");
        rejectVirtualProjectDomain(hasPackagingDomain(packaging), "packaging");
        rejectVirtualProjectDomain(publishing.isPresent(), "publishing");
    }

    private static void validateBomProject(
            Optional<AuthoredWorkspace> workspace,
            AuthoredProject project,
            AuthoredToolchains toolchains,
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> dependencyConstraints,
            Optional<AuthoredDependencyPolicy> dependencyPolicy,
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated) {
        rejectBomDomain(project.identity().javaRelease().isPresent(), "project.java");
        rejectBomDomain(project.metadata().main().isPresent(), "project.main");
        rejectBomDomain(
                build.build().filter(value -> !value.sources().isEmpty()).isPresent(),
                "compilable sources");
        rejectBomDomain(dependencies.isPresent(), "dependencies");
        rejectBomDomain(dependencyConstraints.isPresent(), "dependency constraints");
        rejectBomDomain(dependencyPolicy.isPresent(), "dependency policy");
        rejectBomDomain(build.compiler().isPresent(), "compiler settings");
        rejectBomDomain(build.resources().isPresent(), "resources");
        rejectBomDomain(build.tests().isPresent(), "tests");
        rejectBomDomain(generated.filter(AuthoredManifest::hasGeneratedContent).isPresent(), "generated sources");

        if (workspace.isEmpty()) {
            rejectBomDomain(toolchains.mainJava().isPresent(), "project-local main Java toolchain");
            rejectBomDomain(toolchains.testJava().isPresent(), "project-local test Java toolchain");
        }
    }

    private static boolean hasPackagingDomain(AuthoredPackaging packaging) {
        return packaging.packageSettings().isPresent()
                || packaging.manifest().isPresent()
                || packaging.springBoot().isPresent()
                || packaging.nativeImage().isPresent()
                || packaging.bom().isPresent();
    }

    private static boolean hasGeneratedContent(AuthoredGeneratedSources generated) {
        return !generated.tools().declarations().isEmpty()
                || !generated.presets().openApi().isEmpty()
                || !generated.main().isEmpty()
                || !generated.test().isEmpty();
    }

    private static void rejectVirtualProjectDomain(boolean present, String domain) {
        if (present) {
            throw new IllegalArgumentException(
                    "A virtual workspace root cannot author project-only " + domain + ".");
        }
    }

    private static void rejectBomDomain(boolean present, String domain) {
        if (present) {
            throw new IllegalArgumentException("A BOM cannot author " + domain + ".");
        }
    }
}
