package sh.zolt.manifest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One authored named test suite with optional parallelism and exclusive resource locks. */
public record AuthoredTestSuite(
        List<TestClassPattern> classes,
        List<TestClassPattern> excludeClasses,
        List<String> tags,
        List<String> excludeTags,
        Optional<Integer> workers,
        List<Lock> locks) {
    public AuthoredTestSuite {
        classes = immutableDistinct(classes, "Test suite class patterns");
        excludeClasses = immutableDistinct(
                excludeClasses, "Test suite excluded class patterns");
        tags = immutableDistinctStrings(tags, "Test suite tags");
        excludeTags = immutableDistinctStrings(excludeTags, "Test suite excluded tags");
        workers = Objects.requireNonNull(workers, "Authored test suite workers must not be null.");
        workers.ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException("Test suite workers must be a positive integer.");
            }
        });
        locks = immutableLocks(locks);
        if (classes.isEmpty() && excludeClasses.isEmpty() && tags.isEmpty()
                && excludeTags.isEmpty() && workers.isEmpty() && locks.isEmpty()) {
            throw new IllegalArgumentException("Authored test suite must not be empty.");
        }
    }

    /** One exact test class and its nonempty sorted set of exclusive resource IDs. */
    public record Lock(JavaBinaryClassName className, List<LocalId> resources) {
        public Lock {
            Objects.requireNonNull(className, "Test suite lock class must not be null.");
            resources = ManifestModelValues.sortedDistinctList(
                    resources, "Test suite lock resources");
            if (resources.isEmpty()) {
                throw new IllegalArgumentException(
                        "Test suite lock resources must contain at least one local ID.");
            }
        }
    }

    private static <T> List<T> immutableDistinct(List<T> values, String label) {
        List<T> copy = ManifestModelValues.immutableList(values, label);
        ManifestModelValues.rejectDuplicates(copy, label);
        return copy;
    }

    private static List<String> immutableDistinctStrings(List<String> values, String label) {
        List<String> copy = immutableDistinct(values, label);
        for (String value : copy) {
            ManifestModelValues.requireNonBlank(value, label + " entry");
            ManifestModelValues.rejectControlCharacters(value, label + " entry");
        }
        return copy;
    }

    private static List<Lock> immutableLocks(List<Lock> values) {
        List<Lock> copy = ManifestModelValues.sortedByString(
                values, lock -> lock.className().value(), "Test suite locks");
        Set<JavaBinaryClassName> classes = new HashSet<>();
        for (Lock lock : copy) {
            if (!classes.add(lock.className())) {
                throw new IllegalArgumentException(
                        "Test suite lock class `" + lock.className() + "` must appear only once.");
            }
        }
        return copy;
    }
}
