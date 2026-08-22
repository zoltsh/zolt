package sh.zolt.manifest.authored;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.ManifestModelValues;

/** Authored test-process settings with literal values and fixed-order event outcomes. */
public record AuthoredTestRuntime(
        List<String> jvmArgs,
        Map<String, String> properties,
        Map<EnvironmentVariableName, String> env,
        List<Event> events) {
    private static final String REMOVED_PROJECT_ROOT_PLACEHOLDER = "${project.root}";
    private static final Set<String> RESERVED_PROPERTIES = Set.of("user.dir", "java.class.path");

    public AuthoredTestRuntime {
        jvmArgs = immutableJvmArguments(jvmArgs);
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

    private static List<String> immutableJvmArguments(List<String> values) {
        List<String> copy = ManifestModelValues.immutableList(values, "Test runtime JVM arguments");
        for (String argument : copy) {
            ManifestModelValues.requireNonBlank(argument, "Test runtime JVM argument");
            ManifestModelValues.rejectControlCharacters(argument, "Test runtime JVM argument");
        }
        return copy;
    }

    /**
     * Property names and values are validated here rather than in the legacy settings record, so a
     * manifest the parser accepts always adapts (design §21). Zolt owns the test runner's working
     * directory and classpath, so those two names can never be authored.
     */
    private static Map<String, String> immutableProperties(Map<String, String> values) {
        Map<String, String> copy = ManifestModelValues.immutableSortedMap(
                values,
                ManifestModelValues.CODE_POINT_ORDER,
                "Test runtime property name",
                "Test runtime property value");
        copy.forEach((name, value) -> {
            ManifestModelValues.requireNonBlank(name, "Test runtime property name");
            ManifestModelValues.rejectControlCharacters(name, "Test runtime property name");
            if (!name.equals(name.strip())) {
                throw new IllegalArgumentException(
                        "Test runtime property name `" + name
                                + "` must not have surrounding whitespace.");
            }
            if (RESERVED_PROPERTIES.contains(name)) {
                throw new IllegalArgumentException(
                        "Test runtime property `" + name
                                + "` is reserved; Zolt owns the test runner working directory and classpath.");
            }
            ManifestModelValues.requireNonBlank(
                    value, "Test runtime property value for `" + name + "`");
            ManifestModelValues.rejectControlCharacters(
                    value, "Test runtime property value for `" + name + "`");
        });
        return copy;
    }

    private static Map<EnvironmentVariableName, String> immutableEnvironment(
            Map<EnvironmentVariableName, String> values) {
        Map<EnvironmentVariableName, String> copy = ManifestModelValues.immutableSortedMap(
                values,
                Comparator.naturalOrder(),
                "Test runtime environment name",
                "Test runtime environment value");
        copy.forEach((name, value) -> {
            ManifestModelValues.requireNonBlank(
                    value, "Test runtime environment value for `" + name + "`");
            ManifestModelValues.rejectControlCharacters(
                    value, "Test runtime environment value for `" + name + "`");
        });
        return copy;
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
