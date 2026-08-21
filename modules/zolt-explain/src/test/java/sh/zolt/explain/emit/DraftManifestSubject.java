package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one drafted authored manifest the way the emit tests want to assert on it: by lane, by
 * selector kind, and by identity field. Keeps the assertions about emit behavior rather than about
 * navigating {@code Optional}-heavy authored records.
 */
final class DraftManifestSubject {
    private final AuthoredManifest manifest;

    private DraftManifestSubject(AuthoredManifest manifest) {
        this.manifest = manifest;
    }

    static DraftManifestSubject of(DraftZoltToml draft) {
        return new DraftManifestSubject(draft.manifest());
    }

    static DraftManifestSubject of(AuthoredManifest manifest) {
        return new DraftManifestSubject(manifest);
    }

    AuthoredManifest manifest() {
        return manifest;
    }

    AuthoredProjectIdentity identity() {
        return manifest.project().orElseThrow().identity();
    }

    String name() {
        return identity().name().value();
    }

    Optional<String> group() {
        return identity().group().map(value -> value.value());
    }

    Optional<String> version() {
        return identity().version().map(value -> value.value());
    }

    Optional<Integer> javaRelease() {
        return identity().javaRelease().map(value -> value.value());
    }

    Optional<String> mainClass() {
        return manifest.project().orElseThrow().metadata().main().map(value -> value.value());
    }

    /** Fixed-version declarations in one lane, keyed by coordinate. */
    Map<String, String> fixed(DependencyLane lane) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (AuthoredDependency dependency : inLane(lane)) {
            if (dependency.selector() instanceof DependencySelector.FixedVersion fixed) {
                versions.put(dependency.coordinate().value(), fixed.value());
            }
        }
        return versions;
    }

    /** Platform-managed declarations in one lane. */
    Set<String> managed(DependencyLane lane) {
        return selected(lane, DependencySelector.Managed.class);
    }

    /** Workspace-member declarations in one lane. */
    Set<String> workspaceMembers(DependencyLane lane) {
        return selected(lane, DependencySelector.Workspace.class);
    }

    Set<String> coordinates(DependencyLane lane) {
        Set<String> coordinates = new LinkedHashSet<>();
        inLane(lane).forEach(dependency -> coordinates.add(dependency.coordinate().value()));
        return coordinates;
    }

    AuthoredDependency dependency(DependencyLane lane, String coordinate) {
        return inLane(lane).stream()
                .filter(dependency -> dependency.coordinate().value().equals(coordinate))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + lane + " dependency on `" + coordinate + "` in " + coordinates(lane)));
    }

    Map<String, String> platforms() {
        Map<String, String> platforms = new LinkedHashMap<>();
        manifest.platforms().ifPresent(authored -> authored.entries().forEach((coordinate, selector) -> {
            if (selector instanceof PlatformSelector.FixedVersion fixed) {
                platforms.put(coordinate.value(), fixed.value());
            }
        }));
        return platforms;
    }

    Map<String, String> constraints() {
        Map<String, String> constraints = new LinkedHashMap<>();
        manifest.dependencyConstraints().ifPresent(authored -> authored.entries()
                .forEach((coordinate, constraint) -> {
                    if (constraint.selector()
                            instanceof sh.zolt.manifest.DependencyConstraintSelector.FixedVersion fixed) {
                        constraints.put(coordinate.value(), fixed.value());
                    }
                }));
        return constraints;
    }

    Optional<AuthoredBom> bom() {
        return manifest.packaging().bom();
    }

    Map<String, String> bomVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        bom().flatMap(AuthoredBom::versions).ifPresent(entries -> entries.forEach((coordinate, version) -> {
            if (version.selector() instanceof PlatformSelector.FixedVersion fixed) {
                versions.put(coordinate.value(), fixed.value());
            }
        }));
        return versions;
    }

    Map<String, String> bomImports() {
        Map<String, String> imports = new LinkedHashMap<>();
        bom().flatMap(AuthoredBom::imports).ifPresent(entries -> entries.forEach((coordinate, selector) -> {
            if (selector instanceof PlatformSelector.FixedVersion fixed) {
                imports.put(coordinate.value(), fixed.value());
            }
        }));
        return imports;
    }

    private Set<String> selected(DependencyLane lane, Class<? extends DependencySelector> kind) {
        Set<String> coordinates = new LinkedHashSet<>();
        for (AuthoredDependency dependency : inLane(lane)) {
            if (kind.isInstance(dependency.selector())) {
                coordinates.add(dependency.coordinate().value());
            }
        }
        return coordinates;
    }

    private java.util.List<AuthoredDependency> inLane(DependencyLane lane) {
        return manifest.dependencies().map(AuthoredDependencies::declarations).orElse(java.util.List.of())
                .stream()
                .filter(dependency -> dependency.lane() == lane)
                .toList();
    }
}
