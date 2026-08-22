package sh.zolt.update;

import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds every place a {@code [versions]} alias is referenced in one authored manifest — dependency
 * lanes, platforms, dependency constraints, BOM pins and imports, and named generated-tool version
 * references — so both {@code zolt outdated} (governs list) and {@code zolt update} (alias fan-out
 * warning) share one complete reference scan. Coordinate-bearing references also carry their
 * {@code group:artifact} for version discovery. Results are deduplicated by label in a deterministic
 * order.
 */
public final class AliasReferences {
    private AliasReferences() {
    }

    public static List<AliasReference> referencing(AuthoredManifest manifest, String alias) {
        Map<String, AliasReference> references = new LinkedHashMap<>();
        collectDependencies(manifest, alias, references);
        collectPlatforms(manifest, alias, references);
        collectConstraints(manifest, alias, references);
        collectBom(manifest, alias, references);
        collectGeneratedTools(manifest, alias, references);
        return List.copyOf(references.values());
    }

    public static List<String> referencingLabels(AuthoredManifest manifest, String alias) {
        return referencing(manifest, alias).stream().map(AliasReference::label).toList();
    }

    private static void collectDependencies(
            AuthoredManifest manifest, String alias, Map<String, AliasReference> references) {
        for (AuthoredDependency dependency : manifest.dependencies()
                .map(AuthoredDependencies::declarations)
                .orElseGet(List::of)) {
            if (dependency.selector() instanceof DependencySelector.VersionReference reference
                    && reference.alias().value().equals(alias)) {
                add(
                        references,
                        ManifestSections.lane(dependency.lane()) + "." + dependency.coordinate().value(),
                        Optional.of(dependency.coordinate().value()));
            }
        }
    }

    private static void collectPlatforms(
            AuthoredManifest manifest, String alias, Map<String, AliasReference> references) {
        manifest.platforms()
                .map(AuthoredPlatforms::entries)
                .orElseGet(Map::of)
                .forEach((coordinate, selector) ->
                        addPlatform(references, alias, ManifestSections.PLATFORMS, coordinate, selector));
    }

    private static void collectConstraints(
            AuthoredManifest manifest, String alias, Map<String, AliasReference> references) {
        Map<DependencyCoordinate, AuthoredDependencyConstraint> constraints = manifest.dependencyConstraints()
                .map(AuthoredDependencyConstraints::entries)
                .orElseGet(Map::of);
        constraints.forEach((coordinate, constraint) -> {
            if (constraint.selector() instanceof DependencyConstraintSelector.VersionReference reference
                    && reference.alias().value().equals(alias)) {
                add(
                        references,
                        ManifestSections.DEPENDENCY_CONSTRAINTS + "." + coordinate.value(),
                        Optional.of(coordinate.value()));
            }
        });
    }

    private static void collectBom(
            AuthoredManifest manifest, String alias, Map<String, AliasReference> references) {
        Optional<AuthoredBom> bom = manifest.packaging().bom();
        if (bom.isEmpty()) {
            return;
        }
        bom.orElseThrow().versions().orElseGet(Map::of).forEach((coordinate, version) ->
                addPlatform(references, alias, ManifestSections.BOM_VERSIONS, coordinate, version.selector()));
        bom.orElseThrow().imports().orElseGet(Map::of).forEach((coordinate, selector) ->
                addPlatform(references, alias, ManifestSections.BOM_IMPORTS, coordinate, selector));
    }

    private static void collectGeneratedTools(
            AuthoredManifest manifest, String alias, Map<String, AliasReference> references) {
        manifest.generated().ifPresent(generated -> generated.tools().declarations()
                .forEach((id, tool) -> collectTool(references, alias, id, tool)));
    }

    private static void collectTool(
            Map<String, AliasReference> references,
            String alias,
            LocalId id,
            AuthoredGeneratedTool tool) {
        String section = ManifestSections.generatedTool(id);
        switch (tool) {
            case AuthoredGeneratedTool.OpenApi openApi -> addTool(
                    references, alias, section + ".versionRef", openApi.coordinate(), openApi.version());
            case AuthoredGeneratedTool.Protobuf protobuf -> {
                addTool(
                        references,
                        alias,
                        section + ".protocVersionRef",
                        protobuf.protocCoordinate(),
                        protobuf.protocVersion());
                addTool(
                        references,
                        alias,
                        section + ".grpcVersionRef",
                        protobuf.grpcCoordinate(),
                        protobuf.grpcVersion());
            }
            case AuthoredGeneratedTool.Jvm jvm -> {
                for (GeneratedArtifactRequest request : jvm.coordinates()) {
                    addTool(
                            references,
                            alias,
                            section + ".coordinates",
                            Optional.of(request.coordinate()),
                            Optional.of(request.selector()));
                }
            }
            case AuthoredGeneratedTool.Process ignored -> {
                // A process tool declares no artifact coordinate and cannot reference an alias.
            }
        }
    }

    private static void addTool(
            Map<String, AliasReference> references,
            String alias,
            String label,
            Optional<DependencyCoordinate> coordinate,
            Optional<DependencySelector> selector) {
        if (selector.isEmpty()
                || !(selector.orElseThrow() instanceof DependencySelector.VersionReference reference)
                || !reference.alias().value().equals(alias)) {
            return;
        }
        add(references, label, coordinate.map(DependencyCoordinate::value));
    }

    private static void addPlatform(
            Map<String, AliasReference> references,
            String alias,
            String section,
            DependencyCoordinate coordinate,
            PlatformSelector selector) {
        if (selector instanceof PlatformSelector.VersionReference reference
                && reference.alias().value().equals(alias)) {
            add(references, section + "." + coordinate.value(), Optional.of(coordinate.value()));
        }
    }

    private static void add(Map<String, AliasReference> references, String label, Optional<String> coordinate) {
        references.putIfAbsent(label, new AliasReference(label, coordinate));
    }
}
