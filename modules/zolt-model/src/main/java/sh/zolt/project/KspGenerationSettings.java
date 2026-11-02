package sh.zolt.project;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The resolved KSP2 tool and per-step processor options.
 *
 * <p>The engine and processor closures intentionally use different tool groups. This prevents a
 * processor dependency from replacing an engine dependency (or vice versa) during mediation while
 * still allowing shared artifacts to be represented once in the lockfile.
 */
public record KspGenerationSettings(
        String toolName,
        Optional<String> version,
        Optional<String> versionRef,
        List<KspProcessorSettings> processors,
        Map<String, String> options) {
    public static final String ENGINE_COORDINATE =
            "com.google.devtools.ksp:symbol-processing-aa";

    public KspGenerationSettings {
        toolName = toolName == null ? "" : toolName.strip();
        version = clean(version);
        versionRef = clean(versionRef);
        processors = processors == null ? List.of() : List.copyOf(processors);
        options = sortedCopy(options);

        boolean empty = toolName.isEmpty()
                && version.isEmpty()
                && versionRef.isEmpty()
                && processors.isEmpty()
                && options.isEmpty();
        if (!empty) {
            if (toolName.isEmpty()) {
                throw new IllegalArgumentException("KSP tool name is required.");
            }
            if (version.isEmpty()) {
                throw new IllegalArgumentException("KSP tool version is required.");
            }
            if (processors.isEmpty()) {
                throw new IllegalArgumentException("At least one KSP processor is required.");
            }
            rejectDuplicateProcessors(processors);
        }
    }

    public static KspGenerationSettings empty() {
        return new KspGenerationSettings("", Optional.empty(), Optional.empty(), List.of(), Map.of());
    }

    public boolean configured() {
        return !toolName.isEmpty();
    }

    public String engineGroup() {
        return group("engine");
    }

    public String processorGroup() {
        return group("processors");
    }

    private String group(String lane) {
        if (!configured()) {
            throw new IllegalStateException("KSP tool groups require configured settings.");
        }
        return "ksp:" + toolName + ":" + lane;
    }

    private static Optional<String> clean(Optional<String> value) {
        if (value == null) {
            return Optional.empty();
        }
        return value.filter(candidate -> !candidate.isBlank()).map(String::strip);
    }

    private static Map<String, String> sortedCopy(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("KSP processor option names must be non-empty.");
            }
            if (value == null) {
                throw new IllegalArgumentException(
                        "KSP processor option `" + key + "` must have a value.");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static void rejectDuplicateProcessors(List<KspProcessorSettings> processors) {
        Set<String> coordinates = new HashSet<>();
        for (KspProcessorSettings processor : processors) {
            if (processor == null) {
                throw new IllegalArgumentException("KSP processors must not contain null entries.");
            }
            if (!coordinates.add(processor.coordinate())) {
                throw new IllegalArgumentException(
                        "Duplicate KSP processor coordinate `" + processor.coordinate() + "`.");
            }
        }
    }
}
