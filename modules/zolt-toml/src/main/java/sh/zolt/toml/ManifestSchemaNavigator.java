package sh.zolt.toml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.schema.MutationPolicy;

/** Cross-kind manifest schema resolution and sibling navigation. */
final class ManifestSchemaNavigator {
    private final ManifestSchemaRegistry registry;
    private final Set<List<String>> mutableParents;

    ManifestSchemaNavigator(ManifestSchemaRegistry registry) {
        this.registry = registry;
        LinkedHashSet<List<String>> parents = new LinkedHashSet<>();
        registry.fields().stream()
                .filter(field -> field.mutation() == MutationPolicy.REPLACE_ENTRY)
                .map(field -> field.path().segments())
                .map(path -> List.copyOf(path.subList(0, path.size() - 1)))
                .forEach(parents::add);
        mutableParents = Set.copyOf(parents);
    }

    Resolution resolve(List<String> concretePath) {
        Optional<ManifestSchemaMatch<ManifestField>> field =
                match(concretePath, registry.fields(), ManifestField::path);
        Optional<ManifestSchemaMatch<ManifestSection>> section =
                match(concretePath, registry.sections(), ManifestSection::path);
        if (field.isPresent() && section.isPresent()) {
            int fieldSpecificity = specificity(field.orElseThrow().descriptor().path());
            int sectionSpecificity = specificity(section.orElseThrow().descriptor().path());
            if (fieldSpecificity == sectionSpecificity) {
                throw new IllegalStateException(
                        "Ambiguous manifest field/section match for `"
                                + String.join(".", concretePath) + "`.");
            }
            return fieldSpecificity > sectionSpecificity
                    ? Resolution.field(field.orElseThrow())
                    : Resolution.section(section.orElseThrow());
        }
        if (field.isPresent()) {
            return Resolution.field(field.orElseThrow());
        }
        if (section.isPresent()) {
            return Resolution.section(section.orElseThrow());
        }
        return isStructuralPrefix(concretePath)
                ? Resolution.structural()
                : Resolution.unknown();
    }

    Optional<ManifestSchemaMatch<ManifestSection>> sectionMatch(List<String> concretePath) {
        return match(concretePath, registry.sections(), ManifestSection::path);
    }

    boolean isMutableParent(List<String> concretePath) {
        return mutableParents.contains(concretePath);
    }

    List<String> mutableParent(ManifestField field, List<String> actualPath) {
        if (field.mutation() != MutationPolicy.REPLACE_ENTRY) {
            throw new IllegalArgumentException("Manifest field is not a mutable entry.");
        }
        return List.copyOf(actualPath.subList(0, actualPath.size() - 1));
    }

    Optional<String> reservedBinding(
            ManifestPath pattern,
            Map<String, String> bindings,
            Set<String> descriptorReserved) {
        List<String> segments = pattern.segments();
        for (int index = 0; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (!isPlaceholder(segment)) {
                continue;
            }
            String value = bindings.get(placeholderName(segment));
            if (descriptorReserved.contains(value)) {
                return Optional.of(value);
            }
            if (index == 0) {
                continue;
            }
            List<String> parent = segments.subList(0, index);
            if (parent.stream().anyMatch(ManifestSchemaNavigator::isPlaceholder)) {
                continue;
            }
            Optional<ManifestSection> parentSection = registry.section(new ManifestPath(parent));
            if (parentSection.map(ManifestSection::reservedChildren)
                    .filter(values -> values.contains(value))
                    .isPresent()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    Optional<String> suggestField(List<String> actualPath) {
        if (actualPath.isEmpty()) {
            return Optional.empty();
        }
        List<String> parent = actualPath.subList(0, actualPath.size() - 1);
        String observed = actualPath.getLast();
        List<Candidate> candidates = registry.fields().stream()
                .filter(field -> field.path().segments().size() == actualPath.size())
                .filter(field -> matchesPrefix(field.path().segments(), parent))
                .filter(field -> !isPlaceholder(field.path().segments().getLast()))
                .map(field -> new Candidate(
                        field.path().segments().getLast(), field.canonicalOrder()))
                .toList();
        return nearest(observed, candidates);
    }

    Optional<String> suggestSection(List<String> actualPath) {
        if (actualPath.isEmpty()) {
            return Optional.empty();
        }
        List<String> parent = actualPath.subList(0, actualPath.size() - 1);
        String observed = actualPath.getLast();
        ArrayList<Candidate> candidates = new ArrayList<>();
        registry.sections().forEach(section -> addNextSectionCandidate(
                section.path().segments(), section.canonicalOrder(), parent, candidates));
        return nearest(observed, candidates);
    }

    private boolean isStructuralPrefix(List<String> actual) {
        return java.util.stream.Stream.concat(
                        registry.fields().stream().map(ManifestField::path),
                        registry.sections().stream().map(ManifestSection::path))
                .map(ManifestPath::segments)
                .anyMatch(pattern -> pattern.size() > actual.size()
                        && matchesPrefix(pattern, actual));
    }

    private static void addNextSectionCandidate(
            List<String> pattern,
            int canonicalOrder,
            List<String> parent,
            List<Candidate> candidates) {
        if (pattern.size() <= parent.size() || !matchesPrefix(pattern, parent)) {
            return;
        }
        String next = pattern.get(parent.size());
        if (!isPlaceholder(next)) {
            candidates.add(new Candidate(next, canonicalOrder));
        }
    }

    private static boolean matchesPrefix(List<String> pattern, List<String> actual) {
        if (pattern.size() < actual.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            String expected = pattern.get(index);
            if (!isPlaceholder(expected) && !expected.equals(actual.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> nearest(String observed, List<Candidate> candidates) {
        return candidates.stream()
                .distinct()
                .min(Comparator.comparingInt((Candidate value) -> distance(observed, value.value()))
                        .thenComparingInt(Candidate::canonicalOrder)
                        .thenComparing(Candidate::value))
                .map(Candidate::value);
    }

    private static int distance(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int[] previous = new int[b.length + 1];
        for (int index = 0; index <= b.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= a.length; row++) {
            int[] current = new int[b.length + 1];
            current[0] = row;
            for (int column = 1; column <= b.length; column++) {
                int substitution = previous[column - 1]
                        + (a[row - 1] == b[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return previous[b.length];
    }

    private static int specificity(ManifestPath path) {
        return path.segments().size() - path.placeholderNames().size();
    }

    private static <T> Optional<ManifestSchemaMatch<T>> match(
            List<String> actual,
            List<T> descriptors,
            Function<T, ManifestPath> pathOf) {
        ManifestSchemaMatch<T> best = null;
        int bestSpecificity = -1;
        for (T descriptor : descriptors) {
            ManifestPath pattern = pathOf.apply(descriptor);
            Map<String, String> bindings = bindings(pattern.segments(), actual);
            if (bindings == null) {
                continue;
            }
            int candidateSpecificity = specificity(pattern);
            if (candidateSpecificity == bestSpecificity) {
                throw new IllegalStateException(
                        "Ambiguous manifest schema match for `" + String.join(".", actual) + "`.");
            }
            if (candidateSpecificity > bestSpecificity) {
                best = new ManifestSchemaMatch<>(descriptor, bindings);
                bestSpecificity = candidateSpecificity;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Map<String, String> bindings(List<String> pattern, List<String> actual) {
        if (pattern.size() != actual.size()) {
            return null;
        }
        LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
        for (int index = 0; index < pattern.size(); index++) {
            String expected = pattern.get(index);
            String observed = actual.get(index);
            if (isPlaceholder(expected)) {
                bindings.put(placeholderName(expected), observed);
            } else if (!expected.equals(observed)) {
                return null;
            }
        }
        return bindings;
    }

    static boolean isPlaceholder(String segment) {
        return segment.length() > 2 && segment.startsWith("<") && segment.endsWith(">");
    }

    static String placeholderName(String segment) {
        return segment.substring(1, segment.length() - 1);
    }

    record Resolution(
            Kind kind,
            Optional<ManifestSchemaMatch<ManifestField>> field,
            Optional<ManifestSchemaMatch<ManifestSection>> section) {
        static Resolution field(ManifestSchemaMatch<ManifestField> value) {
            return new Resolution(Kind.FIELD, Optional.of(value), Optional.empty());
        }

        static Resolution section(ManifestSchemaMatch<ManifestSection> value) {
            return new Resolution(Kind.SECTION, Optional.empty(), Optional.of(value));
        }

        static Resolution structural() {
            return new Resolution(Kind.STRUCTURAL_PREFIX, Optional.empty(), Optional.empty());
        }

        static Resolution unknown() {
            return new Resolution(Kind.UNKNOWN, Optional.empty(), Optional.empty());
        }
    }

    enum Kind {
        FIELD,
        SECTION,
        STRUCTURAL_PREFIX,
        UNKNOWN
    }

    private record Candidate(String value, int canonicalOrder) {
    }
}
