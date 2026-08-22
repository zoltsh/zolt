package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;

/** Complete authored §10 domain without effective conventional or workspace defaults. */
public record AuthoredBuildConfiguration(
        Optional<AuthoredBuild> build,
        Optional<AuthoredCompiler> compiler,
        Optional<AuthoredResources> resources,
        Optional<AuthoredTests> tests,
        Optional<AuthoredCoverage> coverage) {
    public AuthoredBuildConfiguration {
        build = Objects.requireNonNull(build, "Authored build section must not be null.");
        compiler = Objects.requireNonNull(compiler, "Authored compiler section must not be null.");
        resources = Objects.requireNonNull(resources, "Authored resources section must not be null.");
        tests = Objects.requireNonNull(tests, "Authored tests domain must not be null.");
        coverage = Objects.requireNonNull(coverage, "Authored coverage section must not be null.");
    }

    public static AuthoredBuildConfiguration empty() {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
