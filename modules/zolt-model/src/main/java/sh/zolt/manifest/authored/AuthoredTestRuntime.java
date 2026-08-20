package sh.zolt.manifest.authored;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.ManifestModelValues;

/** Authored test-process settings with literal values and fixed-order event outcomes. */
public record AuthoredTestRuntime(
        List<String> jvmArgs,
        Map<String, String> properties,
        Map<EnvironmentVariableName, String> env,
        List<Event> events) {
    private static final String REMOVED_PROJECT_ROOT_PLACEHOLDER = "${project.root}";

    public AuthoredTestRuntime {
        jvmArgs = ManifestModelValues.immutableList(jvmArgs, "Test runtime JVM arguments");
        properties = immutableProperties(properties);
        env = immutableEnvironment(env);
        events = immutableEvents(events);
        rejectRemovedProjectRootPlaceholder(jvmArgs, properties, env);
        ManifestModelValues.rejectEnvironmentCaseCollisions(
                env.keySet(), "Test runtime");
        if (jvmArgs.isEmpty() && properties.isEmpty() && env.isEmpty() && events.isEmpty()) {
            throw new IllegalArgumentException("Authored test runtime settings must not be empty.");
        }
    }

    public enum Event {
        PASSED("passed"),
        SKIPPED("skipped"),
        FAILED("failed");

        private final String configValue;

        Event(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    private static Map<String, String> immutableProperties(Map<String, String> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                ManifestModelValues.CODE_POINT_ORDER,
                "Test runtime property name",
                "Test runtime property value");
    }

    private static Map<EnvironmentVariableName, String> immutableEnvironment(
            Map<EnvironmentVariableName, String> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Test runtime environment name",
                "Test runtime environment value");
    }

    private static List<Event> immutableEvents(List<Event> values) {
        ArrayList<Event> copy = new ArrayList<>(
                ManifestModelValues.immutableList(values, "Test runtime events"));
        ManifestModelValues.rejectDuplicates(copy, "Test runtime events");
        copy.sort(Comparator.comparingInt(Enum::ordinal));
        return List.copyOf(copy);
    }

    private static void rejectRemovedProjectRootPlaceholder(
            List<String> jvmArgs,
            Map<String, String> properties,
            Map<EnvironmentVariableName, String> environment) {
        jvmArgs.forEach(value -> rejectRemovedProjectRootPlaceholder(value, "Test runtime JVM argument"));
        properties.forEach((key, value) -> {
            rejectRemovedProjectRootPlaceholder(key, "Test runtime property name");
            rejectRemovedProjectRootPlaceholder(value, "Test runtime property value");
        });
        environment.values().forEach(value ->
                rejectRemovedProjectRootPlaceholder(value, "Test runtime environment value"));
    }

    private static void rejectRemovedProjectRootPlaceholder(String value, String label) {
        if (value.contains(REMOVED_PROJECT_ROOT_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    label + " cannot use the removed `${project.root}` interpolation placeholder.");
        }
    }
}
