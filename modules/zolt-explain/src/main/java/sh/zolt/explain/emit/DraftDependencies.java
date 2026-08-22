package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Accumulates the authored dependency and platform declarations of one drafted manifest.
 *
 * <p>Every value the audit hands over is a string read out of a foreign build file, so each one is
 * validated against the manifest value grammar here rather than trusted. A coordinate, version, or
 * lane the final language cannot express becomes a review note and is dropped from the draft: an
 * emitted manifest that fails to parse would be worse than an honest gap the adopter can close.
 *
 * <p>The authored model enforces one lane per coordinate across the ordinary lanes, which the legacy
 * per-section maps did not. A second declaration of the same coordinate in a different ordinary lane
 * is therefore reported instead of silently emitted twice.
 */
final class DraftDependencies {
    private static final Comparator<AuthoredDependency> CANONICAL_ORDER = Comparator
            .<AuthoredDependency>comparingInt(dependency -> dependency.lane().canonicalOrder())
            .thenComparing(dependency -> dependency.coordinate().value());

    private final Map<DependencyCoordinate, PlatformSelector> platforms = new TreeMap<>();
    private final Map<String, AuthoredDependency> declarations = new LinkedHashMap<>();
    private final Map<DependencyCoordinate, DependencyLane> ordinaryLanes = new HashMap<>();
    private final List<String> notes;

    DraftDependencies(List<String> notes) {
        this.notes = notes;
    }

    boolean hasPlatform() {
        return !platforms.isEmpty();
    }

    void platform(String coordinate, String version) {
        DependencyCoordinate key = coordinate(coordinate, "Imported platform");
        if (key == null) {
            return;
        }
        try {
            platforms.put(key, new PlatformSelector.FixedVersion(version));
        } catch (IllegalArgumentException exception) {
            notes.add("Imported platform `" + coordinate + "` version `" + version
                    + "` is not a valid manifest value: " + exception.getMessage()
                    + " Add it under [platforms] by hand.");
        }
    }

    void fixed(DependencyLane lane, String coordinate, String version) {
        fixed(lane, coordinate, version, AuthoredDependencyMetadata.none());
    }

    void fixed(
            DependencyLane lane,
            String coordinate,
            String version,
            AuthoredDependencyMetadata metadata) {
        DependencyCoordinate key = coordinate(coordinate, "Dependency");
        if (key == null) {
            return;
        }
        DependencySelector selector;
        try {
            selector = new DependencySelector.FixedVersion(version);
        } catch (IllegalArgumentException exception) {
            notes.add("Dependency `" + coordinate + "` version `" + version
                    + "` is not a valid manifest value: " + exception.getMessage()
                    + " Add it by hand with a fixed released version.");
            return;
        }
        declare(lane, key, selector, metadata);
    }

    void managed(DependencyLane lane, String coordinate, AuthoredDependencyMetadata metadata) {
        DependencyCoordinate key = coordinate(coordinate, "Dependency");
        if (key == null) {
            return;
        }
        declare(lane, key, new DependencySelector.Managed(), metadata);
    }

    /** A sibling workspace member: the final language resolves it by identity, not by path. */
    void workspaceMember(DependencyLane lane, String coordinate) {
        DependencyCoordinate key = coordinate(coordinate, "Workspace dependency");
        if (key == null) {
            return;
        }
        declare(lane, key, new DependencySelector.Workspace(), AuthoredDependencyMetadata.none());
    }

    Optional<AuthoredDependencies> dependencies() {
        if (declarations.isEmpty()) {
            return Optional.empty();
        }
        List<AuthoredDependency> sorted = new ArrayList<>(declarations.values());
        sorted.sort(CANONICAL_ORDER);
        return Optional.of(new AuthoredDependencies(sorted));
    }

    Optional<AuthoredPlatforms> platformDomain() {
        return platforms.isEmpty() ? Optional.empty() : Optional.of(new AuthoredPlatforms(platforms));
    }

    private void declare(
            DependencyLane lane,
            DependencyCoordinate coordinate,
            DependencySelector selector,
            AuthoredDependencyMetadata metadata) {
        String key = lane.name() + " " + coordinate.value();
        if (declarations.containsKey(key)) {
            return;
        }
        if (isOrdinary(lane)) {
            DependencyLane existing = ordinaryLanes.putIfAbsent(coordinate, lane);
            if (existing != null && existing != lane) {
                notes.add("Dependency `" + coordinate.value() + "` was declared in both the "
                        + label(existing) + " and " + label(lane)
                        + " lanes; one coordinate resolves to one lane, so only the " + label(existing)
                        + " declaration was emitted. Fold the other scope in by hand.");
                return;
            }
        }
        try {
            declarations.put(key, new AuthoredDependency(lane, coordinate, selector, metadata));
        } catch (IllegalArgumentException exception) {
            notes.add("Dependency `" + coordinate.value() + "` could not be expressed in the "
                    + label(lane) + " lane: " + exception.getMessage() + " Add it by hand.");
        }
    }

    private DependencyCoordinate coordinate(String value, String subject) {
        try {
            return new DependencyCoordinate(value);
        } catch (IllegalArgumentException exception) {
            notes.add(subject + " `" + value + "` is not a valid `group:artifact` coordinate: "
                    + exception.getMessage() + " Add it by hand.");
            return null;
        }
    }

    private static boolean isOrdinary(DependencyLane lane) {
        return lane != DependencyLane.PROCESSOR && lane != DependencyLane.TEST_PROCESSOR;
    }

    private static String label(DependencyLane lane) {
        return lane.name().toLowerCase().replace('_', '-');
    }
}
