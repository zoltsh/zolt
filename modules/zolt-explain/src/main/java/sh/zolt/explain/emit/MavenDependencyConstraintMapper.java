package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Maps Maven {@code dependencyManagement} pins to {@code [dependencies.constraints]} entries. */
final class MavenDependencyConstraintMapper {
    private static final String REASON = "Imported from Maven dependencyManagement.";

    private MavenDependencyConstraintMapper() {
    }

    static Optional<AuthoredDependencyConstraints> map(
            List<MavenDependencyInspection> dependencyManagement,
            List<MavenDependencyInspection> directDependencies,
            List<String> notes) {
        Set<String> directCoordinates = directCoordinates(directDependencies);
        Map<DependencyCoordinate, AuthoredDependencyConstraint> constraints = new TreeMap<>();
        for (MavenDependencyInspection dependency : dependencyManagement) {
            String coordinate = coordinateOf(dependency.coordinate());
            if (directCoordinates.contains(coordinate)) {
                continue;
            }
            if (!dependency.classifier().isBlank()) {
                notes.add(
                        "Managed dependency `" + coordinate + "` declares Maven classifier `"
                                + dependency.classifier()
                                + "`. [dependencies.constraints] cannot express classifier-specific"
                                + " artifacts; review it manually.");
                continue;
            }
            if (!"jar".equals(dependency.type())) {
                notes.add(
                        "Managed dependency `" + coordinate + "` uses Maven type `" + dependency.type()
                                + "`, which [dependencies.constraints] cannot express; review it manually.");
                continue;
            }
            if (dependency.version().isBlank()) {
                continue;
            }
            if (dependency.version().contains("${")) {
                notes.add(
                        "Managed dependency `" + coordinate + "` uses version `" + dependency.version()
                                + "`, which references a property the static audit could not resolve."
                                + " Add the matching [dependencies.constraints] entry manually.");
                continue;
            }
            add(constraints, coordinate, dependency.version(), notes);
        }
        return constraints.isEmpty()
                ? Optional.empty()
                : Optional.of(new AuthoredDependencyConstraints(constraints));
    }

    private static void add(
            Map<DependencyCoordinate, AuthoredDependencyConstraint> constraints,
            String coordinate,
            String version,
            List<String> notes) {
        try {
            constraints.put(
                    new DependencyCoordinate(coordinate),
                    new AuthoredDependencyConstraint(
                            new DependencyConstraintSelector.FixedVersion(version),
                            Optional.of(REASON)));
        } catch (IllegalArgumentException exception) {
            notes.add("Managed dependency `" + coordinate + "` version `" + version
                    + "` is not a valid constraint value: " + exception.getMessage()
                    + " Add the [dependencies.constraints] entry manually.");
        }
    }

    private static Set<String> directCoordinates(List<MavenDependencyInspection> dependencies) {
        Set<String> coordinates = new TreeSet<>();
        for (MavenDependencyInspection dependency : dependencies) {
            coordinates.add(coordinateOf(dependency.coordinate()));
        }
        return coordinates;
    }

    private static String coordinateOf(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1];
        }
        return coordinate;
    }
}
