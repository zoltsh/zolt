package sh.zolt.manifest;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** Authored mediation behavior for dependency version conflicts. */
public enum DependencyConflictPolicy {
    RESOLVE("resolve"),
    WARN("warn"),
    FAIL("fail");

    private final String id;

    DependencyConflictPolicy(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<DependencyConflictPolicy> fromId(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static String supportedIds() {
        return Arrays.stream(values()).map(DependencyConflictPolicy::id).collect(Collectors.joining(", "));
    }
}
