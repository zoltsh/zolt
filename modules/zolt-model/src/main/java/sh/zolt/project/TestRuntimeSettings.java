package sh.zolt.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;

public record TestRuntimeSettings(
        List<String> jvmArgs,
        Map<String, String> systemProperties,
        Map<String, String> environment,
        List<String> events) {
    private static final Set<String> SUPPORTED_EVENTS = Set.of("passed", "skipped", "failed");
    private static final TestRuntimeSettings DEFAULTS =
            new TestRuntimeSettings(List.of(), Map.of(), Map.of(), List.of());

    public TestRuntimeSettings {
        jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs);
        systemProperties = orderedMap(systemProperties);
        environment = orderedMap(environment);
        events = List.copyOf(events == null ? List.of() : events);
        for (String argument : jvmArgs) {
            validateNonBlank("test.runtime.jvmArgs", argument);
        }
        for (String key : systemProperties.keySet()) {
            validateName("test.runtime.properties", key);
            if ("user.dir".equals(key) || "java.class.path".equals(key)) {
                throw new IllegalArgumentException(
                        "Invalid [test.runtime].properties."
                                + key
                                + " in zolt.toml. Zolt owns the test runner user.dir and classpath.");
            }
        }
        for (String key : environment.keySet()) {
            validateName("test.runtime.environment", key);
        }
        for (String event : events) {
            validateEvent("test.runtime.events", event);
        }
    }

    public static void validateEvent(String section, String event) {
        validateNonBlank(section, event);
        if (!SUPPORTED_EVENTS.contains(event)) {
            throw new IllegalArgumentException(
                    "Unsupported test runtime event `"
                            + event
                            + "`. Supported test runtime events are: passed, skipped, failed.");
        }
    }

    public static TestRuntimeSettings defaults() {
        return DEFAULTS;
    }

    public boolean defaultsOnly() {
        return equals(DEFAULTS);
    }

    public Map<String, String> redactedEnvironment() {
        Map<String, String> redacted = new LinkedHashMap<>();
        for (String key : environment.keySet()) {
            redacted.put(key, "<redacted>");
        }
        return redacted;
    }

    private static Map<String, String> orderedMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            validateNonBlank("test runtime key", entry.getKey());
            validateNonBlank("test runtime value for `" + entry.getKey() + "`", entry.getValue());
            ordered.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(ordered);
    }

    private static void validateName(String section, String value) {
        validateNonBlank(section, value);
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "Invalid [" + section + "] key `" + value + "` in zolt.toml. Use a key without surrounding whitespace.");
        }
    }

    private static void validateNonBlank(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " requires a non-empty value.");
        }
    }
}
