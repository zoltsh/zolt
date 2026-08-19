package sh.zolt.toml.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** An immutable structural path in the manifest schema. */
public record ManifestPath(List<String> segments) implements Comparable<ManifestPath> {
    public ManifestPath {
        Objects.requireNonNull(segments, "Manifest path segments are required.");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Manifest path must contain at least one segment.");
        }
        segments = segments.stream()
                .map(ManifestPath::requireSegment)
                .toList();
    }

    public static ManifestPath of(String first, String... remaining) {
        requireSegment(first);
        Objects.requireNonNull(remaining, "Remaining manifest path segments are required.");
        ArrayList<String> segments = new ArrayList<>(remaining.length + 1);
        segments.add(first);
        for (String segment : remaining) {
            segments.add(requireSegment(segment));
        }
        return new ManifestPath(segments);
    }

    public ManifestPath child(String segment) {
        ArrayList<String> childSegments = new ArrayList<>(segments);
        childSegments.add(requireSegment(segment));
        return new ManifestPath(childSegments);
    }

    @Override
    public int compareTo(ManifestPath other) {
        Objects.requireNonNull(other, "Manifest path to compare is required.");
        int sharedLength = Math.min(segments.size(), other.segments.size());
        for (int index = 0; index < sharedLength; index++) {
            int comparison = segments.get(index).compareTo(other.segments.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(segments.size(), other.segments.size());
    }

    @Override
    public String toString() {
        return String.join(".", segments);
    }

    static String requireSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("Manifest path segments must not be blank.");
        }
        if (!segment.equals(segment.strip())) {
            throw new IllegalArgumentException("Manifest path segments must not have surrounding whitespace.");
        }
        if (segment.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Manifest path segments must not contain control characters.");
        }
        return segment;
    }
}
