package sh.zolt.cli.command.config;

import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.manifest.effective.ValueOrigin;
import sh.zolt.project.toolchain.JavaFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The shared line and value vocabulary of both {@code zolt config show} views (design §20.2).
 *
 * <p>One heading, field, and origin renderer keeps the authored and effective reports reading as one
 * report, so a value spells the same way whichever view printed it.
 */
final class ConfigShowValues {
    private static final String INDENT = "  ";

    private ConfigShowValues() {
    }

    static void section(StringBuilder out, String heading) {
        out.append('\n').append(heading).append('\n');
    }

    static void field(StringBuilder out, String label, String value) {
        out.append(INDENT).append(label).append(": ").append(value).append('\n');
    }

    /** One effective value with the origin label and source location the effective view reports. */
    static <T> void origin(
            StringBuilder out, String label, EffectiveValue<T> value, Function<T, String> render) {
        out.append(INDENT)
                .append(label)
                .append(": ")
                .append(render.apply(value.value()))
                .append(" (")
                .append(originLabel(value.origin()))
                .append(value.source().map(source -> ": " + source(source)).orElse(""))
                .append(")\n");
    }

    static String describe(
            Optional<String> version,
            Optional<String> distribution,
            Optional<String> features,
            Optional<String> policy) {
        List<String> parts = new ArrayList<>();
        version.ifPresent(value -> parts.add("version " + value));
        distribution.ifPresent(value -> parts.add("distribution " + value));
        features.ifPresent(value -> parts.add("features " + value));
        policy.ifPresent(value -> parts.add("policy " + value));
        return parts.isEmpty() ? "declared" : String.join(", ", parts);
    }

    static String repository(DependencyRepository repository) {
        return repository.url().value()
                + repository.credentials().map(id -> " (credentials " + id.value() + ")").orElse("");
    }

    static String credential(RepositoryCredential credential) {
        return switch (credential) {
            case RepositoryCredential.BearerToken bearer ->
                    "bearer token from $" + bearer.tokenEnvironment().value();
            case RepositoryCredential.Basic basic -> "basic from $"
                    + basic.usernameEnvironment().value() + " and $" + basic.passwordEnvironment().value();
        };
    }

    static String platform(PlatformSelector selector) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference -> "versionRef " + reference.alias().value();
        };
    }

    static String license(ProjectLicense license) {
        return switch (license) {
            case ProjectLicense.Identifier identifier -> identifier.id();
            case ProjectLicense.Metadata metadata -> metadata.id()
                    .or(metadata::name)
                    .orElse("custom");
        };
    }

    static String features(Set<JavaFeature> features) {
        return features.isEmpty() ? "none" : join(features.stream().map(JavaFeature::id).toList());
    }

    static String percentage(CoveragePercentage percentage) {
        double value = percentage.value();
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }

    static String join(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String originLabel(ValueOrigin origin) {
        return switch (origin) {
            case AUTHORED -> "authored";
            case INHERITED -> "inherited";
            case BUILT_IN -> "built-in";
        };
    }

    private static String source(ManifestSource source) {
        return source.manifestPath() + " " + String.join(".", source.fieldPath());
    }
}
