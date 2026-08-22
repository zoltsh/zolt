package sh.zolt.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Cross-package implementation support for final manifest values.
 *
 * <p>This type is public only so the authored and effective model packages can share the exact
 * same immutable-copy semantics. It is not a manifest-domain API.
 */
public final class ManifestModelValues {
    public static final Comparator<String> CODE_POINT_ORDER = ManifestModelValues::compareCodePoints;

    private ManifestModelValues() {}

    public static <T> List<T> immutableList(List<T> values, String label) {
        Objects.requireNonNull(values, label + " must not be null.");
        for (T value : values) {
            Objects.requireNonNull(value, label + " must not contain null values.");
        }
        return List.copyOf(values);
    }

    /**
     * An order-preserving immutable copy that rejects duplicates.
     *
     * <p>Design §5.5: an order-bearing authored array — source roots, resource roots, generator
     * inputs — keeps exactly the order the author wrote, because effective semantics read it (the
     * first source root is the legacy primary root) and canonical output must round-trip it. Only
     * truly set-like collections use {@link #sortedDistinctList}.
     */
    public static <T> List<T> orderedDistinctList(List<T> values, String label) {
        List<T> copy = immutableList(values, label);
        rejectDuplicates(copy, label);
        return copy;
    }

    /** A sorted immutable copy that rejects duplicates, for truly set-like collections only. */
    public static <T extends Comparable<? super T>> List<T> sortedDistinctList(
            List<T> values, String label) {
        List<T> copy = new ArrayList<>(immutableList(values, label));
        Set<T> seen = new HashSet<>();
        for (T value : copy) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException(label + " must not contain duplicate `" + value + "`.");
            }
        }
        Collections.sort(copy);
        return List.copyOf(copy);
    }

    public static <K, V> Map<K, V> immutableSortedMap(
            Map<K, V> values,
            Comparator<? super K> comparator,
            String keyLabel,
            String valueLabel) {
        Objects.requireNonNull(values, "Authored map must not be null.");
        ArrayList<Map.Entry<K, V>> entries = new ArrayList<>(values.entrySet());
        for (Map.Entry<K, V> entry : entries) {
            Objects.requireNonNull(entry.getKey(), keyLabel + " must not be null.");
            Objects.requireNonNull(entry.getValue(), valueLabel + " must not be null.");
        }
        entries.sort(Map.Entry.comparingByKey(comparator));
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    public static void rejectDuplicates(List<?> values, String label) {
        HashSet<Object> seen = new HashSet<>();
        for (Object value : values) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException(label + " must not contain duplicate `" + value + "`.");
            }
        }
    }

    public static void rejectEnvironmentCaseCollisions(
            Iterable<EnvironmentVariableName> names, String context) {
        Map<String, EnvironmentVariableName> spellingByFoldedName = new HashMap<>();
        for (EnvironmentVariableName name : names) {
            String folded = asciiLowercase(name.value());
            EnvironmentVariableName existing = spellingByFoldedName.putIfAbsent(folded, name);
            if (existing != null && !existing.equals(name)) {
                throw new IllegalArgumentException(
                        context + " environment-variable names `" + existing + "` and `" + name
                                + "` differ only by ASCII case.");
            }
        }
    }

    public static <T> List<T> sortedByString(
            List<T> values, Function<T, String> value, String label) {
        ArrayList<T> copy = new ArrayList<>(immutableList(values, label));
        copy.sort((left, right) -> CODE_POINT_ORDER.compare(value.apply(left), value.apply(right)));
        return List.copyOf(copy);
    }

    public static void requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
    }

    public static void rejectControlCharacters(String value, String label) {
        for (int codePoint : value.codePoints().toArray()) {
            if (codePoint == 0 || Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(label + " must not contain NUL or control characters.");
            }
        }
    }

    private static int compareCodePoints(String left, String right) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int shared = Math.min(leftPoints.length, rightPoints.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(leftPoints[index], rightPoints[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
    }

    private static String asciiLowercase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character >= 'A' && character <= 'Z'
                    ? (char) (character + ('a' - 'A'))
                    : character);
        }
        return result.toString();
    }
}
