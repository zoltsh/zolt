package sh.zolt.manifest.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Publishes an adapter projection as an iteration-order carrier.
 *
 * <p>{@code Map.copyOf} and {@code Set.copyOf} return {@code java.util.ImmutableCollections} values
 * whose iteration order is randomized per JVM by a process-wide salt. Anything downstream that
 * renders such a collection — {@code toString()} in a fingerprint, a report, a diff — therefore
 * produces different bytes on every run for identical inputs. Package evidence hashes exactly these
 * projections, so a salted copy makes {@code zolt check} disagree with the {@code zolt package} run
 * that produced the artifact, and disagree with itself on the next run.
 *
 * <p>Every projection the adapter publishes is built in a deterministic order upstream — authored
 * order, or a sorted effective map — so preserving that order is both correct and free.
 * {@link ProjectConfigRepositories} already documents this for the repository universe, where the
 * order is authored policy; these carriers exist so no other projection can reintroduce the hazard.
 */
final class ProjectConfigOrder {
    private ProjectConfigOrder() {
    }

    static <K, V> Map<K, V> map(Map<K, V> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    static <T> Set<T> set(Set<T> values) {
        return values == null || values.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
