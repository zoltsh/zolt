package sh.zolt.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authored {@code [native]} fields before project-name and output defaults are applied. */
public record AuthoredNativeImage(
        Optional<String> name,
        Optional<ManifestRelativePath> output,
        Optional<List<String>> args) {
    private static final ManifestRelativePath DEFAULT_OUTPUT = new ManifestRelativePath("native");

    public AuthoredNativeImage {
        name = Objects.requireNonNull(name, "Authored native image name must not be null.");
        name.ifPresent(value -> ManifestModelValues.requireNonBlank(value, "Native image name"));
        output = Objects.requireNonNull(output, "Authored native image output must not be null.");
        args = Objects.requireNonNull(args, "Authored native image arguments must not be null.")
                .map(values -> ManifestModelValues.immutableList(values, "Native image arguments"));
        boolean nondefaultOutput = output.filter(value -> !value.equals(DEFAULT_OUTPUT)).isPresent();
        boolean nondefaultArguments = args.filter(values -> !values.isEmpty()).isPresent();
        if (name.isEmpty() && !nondefaultOutput && !nondefaultArguments) {
            throw new IllegalArgumentException(
                    "Authored native image settings must contain at least one nondefault field.");
        }
    }
}
