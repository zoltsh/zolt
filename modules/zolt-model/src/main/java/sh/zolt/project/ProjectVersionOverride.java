package sh.zolt.project;

import java.util.Optional;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;

/**
 * Single owner of the build-time version override read from {@code ZOLT_VERSION_OVERRIDE}.
 *
 * <p>A nightly build exports the value computed by {@code scripts/nightly-native-version} into this
 * env var so the archive filename, the embedded {@code VERSION} file, {@code release-manifest.json},
 * and {@code zolt --version} all stamp the same nightly string instead of the compiled-in
 * {@code 0.1.0-SNAPSHOT}. When the env var is unset, every artifact keeps the compiled default and
 * existing tests are unchanged.
 *
 * <p>An invalid override is rejected up front with an actionable {@code next:} remediation so a
 * misconfigured nightly job fails before any archive is written. The accepted shape mirrors the
 * version grammar that {@code scripts/install-zolt} and {@code scripts/publish-native-release}
 * accept: a {@code <base>} semver (optionally with a pre-release suffix) or a
 * {@code <base>-nightly.YYYYMMDD.<commit>} nightly string or
 * {@code <base>-zap.YYYYMMDD.<commit>} zap dev-channel string.
 */
public final class ProjectVersionOverride {
    public static final String ENV_VAR = "ZOLT_VERSION_OVERRIDE";
    public static final String BUILD_PROPERTY = "zolt.build.version";

    private ProjectVersionOverride() {
    }

    /**
     * Reads and validates the override from the process environment.
     *
     * @return the validated override, or empty when {@code ZOLT_VERSION_OVERRIDE} is unset or blank.
     * @throws ActionableException when the override is set but does not match the accepted shape.
     */
    public static Optional<String> fromEnvironment() {
        return fromValue(System.getenv(ENV_VAR));
    }

    /**
     * Validates a candidate override value (the raw env value or a test-supplied value).
     *
     * @return the trimmed override, or empty when {@code value} is null or blank.
     * @throws ActionableException when {@code value} does not match the accepted shape.
     */
    public static Optional<String> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            return Optional.of(new ZoltVersion(trimmed).value());
        } catch (IllegalArgumentException exception) {
            throw new ActionableException(ActionableError.of(
                    ENV_VAR + " value `" + trimmed + "` is not a valid Zolt version.",
                    "Set " + ENV_VAR
                            + " to a base version like `0.1.0`, a nightly version like"
                            + " `0.1.0-nightly.YYYYMMDD.<commit>`, or a zap version like"
                            + " `0.1.0-zap.YYYYMMDD.<commit>` (7-40 hex commit characters)."));
        }
    }

    /**
     * Resolves the effective version string for {@code --version}: the validated override when set,
     * otherwise the compiled-in default.
     */
    public static String resolveVersion(String compiledDefault) {
        return fromEnvironment().orElse(compiledDefault);
    }

    public static String resolveBuildVersion(String buildVersion, String compiledDefault) {
        return fromValue(buildVersion)
                .or(() -> fromEnvironment())
                .orElse(compiledDefault);
    }

    /**
     * Applies the validated override to a parsed config so {@code config.project().version()} returns
     * the override. When the override is unset the config is returned unchanged.
     */
    public static ProjectConfig apply(ProjectConfig config) {
        return fromEnvironment()
                .map(config::withVersion)
                .orElse(config);
    }
}
