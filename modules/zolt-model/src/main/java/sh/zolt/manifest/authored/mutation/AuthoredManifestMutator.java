package sh.zolt.manifest.authored.mutation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;

/** Typed set/remove operations for every source-editable final manifest entry. */
public final class AuthoredManifestMutator {
    private AuthoredManifestMutator() {
    }

    public static AuthoredManifest setVersionAlias(
            AuthoredManifest manifest,
            LocalId id,
            VersionAliasValue value) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(id, "Version alias ID is required.");
        Objects.requireNonNull(value, "Version alias value is required.");
        Map<LocalId, VersionAliasValue> entries = manifest.versions()
                .map(AuthoredVersionAliases::entries)
                .orElseGet(Map::of);
        if (value.equals(entries.get(id))) {
            return manifest;
        }
        return rebuild(
                manifest,
                Optional.of(new AuthoredVersionAliases(with(entries, id, value))),
                manifest.platforms(),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                manifest.packaging());
    }

    public static AuthoredManifest removeVersionAlias(
            AuthoredManifest manifest,
            LocalId id) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(id, "Version alias ID is required.");
        if (manifest.versions().isEmpty()
                || !manifest.versions().orElseThrow().entries().containsKey(id)) {
            return manifest;
        }
        Map<LocalId, VersionAliasValue> entries = without(
                manifest.versions().orElseThrow().entries(), id);
        return rebuild(
                manifest,
                Optional.of(new AuthoredVersionAliases(entries)),
                manifest.platforms(),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                manifest.packaging());
    }

    public static AuthoredManifest setPlatform(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate,
            PlatformSelector selector) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "Platform coordinate is required.");
        Objects.requireNonNull(selector, "Platform selector is required.");
        Map<DependencyCoordinate, PlatformSelector> entries = manifest.platforms()
                .map(AuthoredPlatforms::entries)
                .orElseGet(Map::of);
        if (selector.equals(entries.get(coordinate))) {
            return manifest;
        }
        return rebuild(
                manifest,
                manifest.versions(),
                Optional.of(new AuthoredPlatforms(with(entries, coordinate, selector))),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                manifest.packaging());
    }

    public static AuthoredManifest removePlatform(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "Platform coordinate is required.");
        if (manifest.platforms().isEmpty()
                || !manifest.platforms().orElseThrow().entries().containsKey(coordinate)) {
            return manifest;
        }
        Map<DependencyCoordinate, PlatformSelector> entries = without(
                manifest.platforms().orElseThrow().entries(), coordinate);
        return rebuild(
                manifest,
                manifest.versions(),
                Optional.of(new AuthoredPlatforms(entries)),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                manifest.packaging());
    }

    public static AuthoredManifest setDependency(
            AuthoredManifest manifest,
            AuthoredDependency dependency) {
        manifest = requireManifest(manifest);
        Optional<AuthoredDependencies> dependencies = AuthoredDependencyMutations.set(
                manifest.dependencies(),
                Objects.requireNonNull(dependency, "Authored dependency is required."));
        return dependencies.equals(manifest.dependencies())
                ? manifest
                : rebuild(
                        manifest,
                        manifest.versions(),
                        manifest.platforms(),
                        dependencies,
                        manifest.dependencyConstraints(),
                        manifest.packaging());
    }

    public static AuthoredManifest removeDependency(
            AuthoredManifest manifest,
            DependencyLane lane,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Optional<AuthoredDependencies> dependencies = AuthoredDependencyMutations.remove(
                manifest.dependencies(), lane, coordinate);
        return dependencies.equals(manifest.dependencies())
                ? manifest
                : rebuild(
                        manifest,
                        manifest.versions(),
                        manifest.platforms(),
                        dependencies,
                        manifest.dependencyConstraints(),
                        manifest.packaging());
    }

    public static AuthoredManifest moveDependency(
            AuthoredManifest manifest,
            DependencyLane sourceLane,
            DependencyLane targetLane,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Optional<AuthoredDependencies> dependencies = AuthoredDependencyMutations.move(
                manifest.dependencies(), sourceLane, targetLane, coordinate);
        return rebuild(
                manifest,
                manifest.versions(),
                manifest.platforms(),
                dependencies,
                manifest.dependencyConstraints(),
                manifest.packaging());
    }

    public static AuthoredManifest setDependencyConstraint(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate,
            AuthoredDependencyConstraint constraint) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "Dependency constraint coordinate is required.");
        Objects.requireNonNull(constraint, "Dependency constraint is required.");
        Map<DependencyCoordinate, AuthoredDependencyConstraint> entries =
                manifest.dependencyConstraints()
                        .map(AuthoredDependencyConstraints::entries)
                        .orElseGet(Map::of);
        if (constraint.equals(entries.get(coordinate))) {
            return manifest;
        }
        return rebuild(
                manifest,
                manifest.versions(),
                manifest.platforms(),
                manifest.dependencies(),
                Optional.of(new AuthoredDependencyConstraints(
                        with(entries, coordinate, constraint))),
                manifest.packaging());
    }

    public static AuthoredManifest removeDependencyConstraint(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "Dependency constraint coordinate is required.");
        if (manifest.dependencyConstraints().isEmpty()
                || !manifest.dependencyConstraints().orElseThrow().entries().containsKey(coordinate)) {
            return manifest;
        }
        Map<DependencyCoordinate, AuthoredDependencyConstraint> entries = without(
                manifest.dependencyConstraints().orElseThrow().entries(), coordinate);
        return rebuild(
                manifest,
                manifest.versions(),
                manifest.platforms(),
                manifest.dependencies(),
                Optional.of(new AuthoredDependencyConstraints(entries)),
                manifest.packaging());
    }

    public static AuthoredManifest setBomVersion(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate,
            AuthoredBom.Version version) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "BOM version coordinate is required.");
        Objects.requireNonNull(version, "BOM version is required.");
        AuthoredBom bom = manifest.packaging().bom().orElse(null);
        Map<DependencyCoordinate, AuthoredBom.Version> entries = bom == null
                ? Map.of()
                : bom.versions().orElseGet(Map::of);
        if (version.equals(entries.get(coordinate))) {
            return manifest;
        }
        AuthoredBom updated = new AuthoredBom(
                bom == null ? Optional.empty() : bom.members(),
                Optional.of(with(entries, coordinate, version)),
                bom == null ? Optional.empty() : bom.imports());
        return withBom(manifest, updated);
    }

    public static AuthoredManifest removeBomVersion(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "BOM version coordinate is required.");
        Optional<AuthoredBom> bom = manifest.packaging().bom();
        if (bom.isEmpty()
                || bom.orElseThrow().versions().isEmpty()
                || !bom.orElseThrow().versions().orElseThrow().containsKey(coordinate)) {
            return manifest;
        }
        AuthoredBom current = bom.orElseThrow();
        return withBom(manifest, new AuthoredBom(
                current.members(),
                Optional.of(without(current.versions().orElseThrow(), coordinate)),
                current.imports()));
    }

    public static AuthoredManifest setBomImport(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate,
            PlatformSelector selector) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "BOM import coordinate is required.");
        Objects.requireNonNull(selector, "BOM import selector is required.");
        AuthoredBom bom = manifest.packaging().bom().orElse(null);
        Map<DependencyCoordinate, PlatformSelector> entries = bom == null
                ? Map.of()
                : bom.imports().orElseGet(Map::of);
        if (selector.equals(entries.get(coordinate))) {
            return manifest;
        }
        AuthoredBom updated = new AuthoredBom(
                bom == null ? Optional.empty() : bom.members(),
                bom == null ? Optional.empty() : bom.versions(),
                Optional.of(with(entries, coordinate, selector)));
        return withBom(manifest, updated);
    }

    public static AuthoredManifest removeBomImport(
            AuthoredManifest manifest,
            DependencyCoordinate coordinate) {
        manifest = requireManifest(manifest);
        Objects.requireNonNull(coordinate, "BOM import coordinate is required.");
        Optional<AuthoredBom> bom = manifest.packaging().bom();
        if (bom.isEmpty()
                || bom.orElseThrow().imports().isEmpty()
                || !bom.orElseThrow().imports().orElseThrow().containsKey(coordinate)) {
            return manifest;
        }
        AuthoredBom current = bom.orElseThrow();
        return withBom(manifest, new AuthoredBom(
                current.members(),
                current.versions(),
                Optional.of(without(current.imports().orElseThrow(), coordinate))));
    }

    private static AuthoredManifest withBom(AuthoredManifest manifest, AuthoredBom bom) {
        AuthoredPackaging packaging = manifest.packaging();
        AuthoredPackaging updated = new AuthoredPackaging(
                packaging.packageSettings(),
                packaging.manifest(),
                packaging.springBoot(),
                packaging.nativeImage(),
                Optional.of(bom));
        return rebuild(
                manifest,
                manifest.versions(),
                manifest.platforms(),
                manifest.dependencies(),
                manifest.dependencyConstraints(),
                updated);
    }

    private static AuthoredManifest rebuild(
            AuthoredManifest source,
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredPlatforms> platforms,
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            AuthoredPackaging packaging) {
        return new AuthoredManifest(
                source.workspace(), source.project(), source.toolchains(),
                versions, source.repositories(), source.credentials(), platforms,
                dependencies, constraints, source.dependencyPolicy(),
                source.build(), source.generated(), packaging,
                source.publishing(), source.commands());
    }

    private static AuthoredManifest requireManifest(AuthoredManifest manifest) {
        return Objects.requireNonNull(manifest, "Authored manifest is required.");
    }

    private static <K, V> Map<K, V> with(Map<K, V> source, K key, V value) {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private static <K, V> Map<K, V> without(Map<K, V> source, K key) {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>(source);
        copy.remove(key);
        return copy;
    }
}
