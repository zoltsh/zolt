package sh.zolt.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record TestSuiteSettings(
        List<String> includeClassname,
        List<String> excludeClassname,
        List<String> includeTag,
        List<String> excludeTag,
        boolean parallelSafe,
        int maxWorkers,
        Map<String, List<String>> resourceLocks) {
    public TestSuiteSettings {
        includeClassname = copyAndValidatePatterns("test.suites.classes", includeClassname);
        excludeClassname = copyAndValidatePatterns("test.suites.excludeClasses", excludeClassname);
        includeTag = copyAndValidateTags("test.suites.tags", includeTag);
        excludeTag = copyAndValidateTags("test.suites.excludeTags", excludeTag);
        if (maxWorkers < 1) {
            throw new IllegalArgumentException("test.suites.workers must be greater than zero.");
        }
        resourceLocks = copyAndValidateResourceLocks(resourceLocks);
    }

    public TestSuiteSettings(
            List<String> includeClassname,
            List<String> excludeClassname,
            List<String> includeTag,
            List<String> excludeTag) {
        this(includeClassname, excludeClassname, includeTag, excludeTag, false, 1, Map.of());
    }

    public static TestSuiteSettings empty() {
        return new TestSuiteSettings(List.of(), List.of(), List.of(), List.of(), false, 1, Map.of());
    }

    private static List<String> copyAndValidatePatterns(String label, List<String> values) {
        List<String> copied = List.copyOf(values == null ? List.of() : values);
        for (String value : copied) {
            validateNonBlank(label, value);
            if (containsControlOrWhitespace(value)) {
                throw new IllegalArgumentException(
                        "Invalid [" + label + "] value `" + value + "`. Patterns must not contain whitespace or control characters.");
            }
        }
        return copied;
    }

    private static List<String> copyAndValidateTags(String label, List<String> values) {
        List<String> copied = List.copyOf(values == null ? List.of() : values);
        for (String value : copied) {
            validateNonBlank(label, value);
            if (containsControlOrWhitespace(value) || value.indexOf(',') >= 0) {
                throw new IllegalArgumentException(
                        "Invalid [" + label + "] value `" + value + "`. Tags must not contain whitespace, commas, or control characters.");
            }
        }
        return copied;
    }

    private static void validateNonBlank(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " requires a non-empty value.");
        }
    }

    private static Map<String, List<String>> copyAndValidateResourceLocks(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : new TreeMap<>(values).entrySet()) {
            String className = entry.getKey();
            validateNonBlank("test.suites.locks class name", className);
            if (containsControlOrWhitespace(className)) {
                throw new IllegalArgumentException(
                        "Invalid [test.suites.locks] class name `" + className + "`. Class names must not contain whitespace or control characters.");
            }
            List<String> locks = List.copyOf(entry.getValue() == null ? List.of() : entry.getValue());
            if (locks.isEmpty()) {
                throw new IllegalArgumentException(
                        "test.suites.locks." + className + " requires at least one resource lock.");
            }
            for (String lock : locks) {
                validateNonBlank("test.suites.locks." + className, lock);
                if (containsControlOrWhitespace(lock)) {
                    throw new IllegalArgumentException(
                            "Invalid [test.suites.locks] resource lock `" + lock + "`. Resource lock names must not contain whitespace or control characters.");
                }
            }
            copied.put(className, locks);
        }
        return Collections.unmodifiableMap(copied);
    }

    private static boolean containsControlOrWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }
}
