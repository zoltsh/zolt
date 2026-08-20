package sh.zolt.manifest.authored;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;

/** One authored manual process from {@code [tasks.<id>]}, excluding its map-owned ID. */
public record AuthoredTask(
        Optional<String> description,
        List<String> run,
        Optional<ManifestRelativePath> cwd,
        Map<EnvironmentVariableName, String> env) {
    public AuthoredTask {
        description = Objects.requireNonNull(description, "Task description must not be null.");
        description.ifPresent(value ->
                ManifestModelValues.requireNonBlank(value, "Task description"));
        run = ManifestModelValues.immutableList(run, "Task run arguments");
        if (run.isEmpty()) {
            throw new IllegalArgumentException("Task run arguments must not be empty.");
        }
        ManifestModelValues.requireNonBlank(run.getFirst(), "Task executable");
        rejectNul(run, "Task run argument");
        cwd = Objects.requireNonNull(cwd, "Task working directory must not be null.");
        env = ManifestModelValues.immutableSortedMap(
                env,
                Comparator.naturalOrder(),
                "Task environment-variable name",
                "Task environment-variable value");
        ManifestModelValues.rejectEnvironmentCaseCollisions(env.keySet(), "Task");
        rejectNul(env.values(), "Task environment value");
    }

    private static void rejectNul(Iterable<String> values, String label) {
        for (String value : values) {
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(label + " must not contain NUL.");
            }
        }
    }
}
