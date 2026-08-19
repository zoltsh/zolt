package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Closed set of authored domains that never inherit from a workspace root. */
public record ProjectLocalDomains(
        AuthoredProjectMetadata metadata,
        Optional<AuthoredDependencies> dependencies,
        Optional<AuthoredDependencyConstraints> dependencyConstraints,
        Optional<AuthoredDependencyPolicy> dependencyPolicy,
        Optional<AuthoredBuild> build,
        Optional<AuthoredCompiler> compiler,
        Optional<AuthoredResources> resources,
        Optional<AuthoredTests> tests,
        Optional<AuthoredGeneratedSources> generated,
        AuthoredPackaging packaging,
        Optional<AuthoredPublishing> publishing) {
    public ProjectLocalDomains {
        metadata = Objects.requireNonNull(metadata, "Project-local metadata must not be null.");
        dependencies = Objects.requireNonNull(
                dependencies, "Project-local dependencies must not be null.");
        dependencyConstraints = Objects.requireNonNull(
                dependencyConstraints, "Project-local dependency constraints must not be null.");
        dependencyPolicy = Objects.requireNonNull(
                dependencyPolicy, "Project-local dependency policy must not be null.");
        build = Objects.requireNonNull(build, "Project-local build settings must not be null.");
        compiler = Objects.requireNonNull(
                compiler, "Project-local compiler settings must not be null.");
        resources = Objects.requireNonNull(resources, "Project-local resources must not be null.");
        tests = Objects.requireNonNull(tests, "Project-local tests must not be null.");
        generated = Objects.requireNonNull(
                generated, "Project-local generated sources must not be null.");
        packaging = Objects.requireNonNull(packaging, "Project-local packaging must not be null.");
        publishing = Objects.requireNonNull(
                publishing, "Project-local publishing must not be null.");
    }
}
