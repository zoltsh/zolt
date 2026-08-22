package sh.zolt.explain.emit;

import sh.zolt.explain.gradle.GradleDependencyInspection;
import sh.zolt.explain.gradle.GradleProjectInspection;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredManifest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Drafts a {@code [bom]} member from a Gradle {@code java-platform} project: {@code platform(...)}
 * imports become {@code [bom.imports]} and {@code constraints { }} pins become {@code [bom.versions]}.
 * Plain dependencies (a {@code java-platform} with {@code allowDependencies()}) become a review note,
 * because a Zolt BOM carries no dependencies. Mirrors {@link MavenBomDraftMapper}.
 */
final class GradleBomDraftMapper {
    private GradleBomDraftMapper() {
    }

    static boolean isBom(GradleProjectInspection primary) {
        return primary.plugins().stream().anyMatch(plugin -> "java-platform".equals(plugin.id()));
    }

    static DraftZoltToml map(GradleProjectInspection primary, List<String> notes) {
        Map<DependencyCoordinate, PlatformSelector> imports = new TreeMap<>();
        for (GradleDependencyInspection dependency : primary.dependencies()) {
            if (!dependency.isPlatform()) {
                notePlainDependency(dependency, notes);
                continue;
            }
            String resolved = dependency.resolvedCoordinate();
            if (resolved == null || resolved.isBlank()) {
                notes.add("Gradle java-platform import in `" + dependency.configuration() + "` (`"
                        + dependency.notation() + "`) did not resolve to a coordinate; add it under"
                        + " [bom.imports] by hand.");
                continue;
            }
            String coordinate = GradleInspectionMapper.coordinateOf(resolved);
            DraftBomEntries.addImport(
                    imports, coordinate, GradleInspectionMapper.versionOf(resolved), notes);
            if (dependency.platformKind() == GradleDependencyInspection.PlatformKind.ENFORCED_PLATFORM) {
                notes.add("Gradle `enforcedPlatform(" + coordinate + ")` was recorded as a [bom.imports]"
                        + " entry; a published BOM composes imports without Gradle's enforced-override"
                        + " semantics, so review whether this coordinate belongs in [bom.versions] instead.");
            }
        }

        Map<DependencyCoordinate, AuthoredBom.Version> versions = new TreeMap<>();
        for (GradleDependencyInspection constraint : primary.constraints()) {
            String resolved = constraint.resolvedCoordinate();
            if (resolved == null || resolved.isBlank()) {
                notes.add("Gradle java-platform constraint in `" + constraint.configuration() + "` (`"
                        + constraint.notation() + "`) did not resolve to a coordinate; add it under"
                        + " [bom.versions] by hand.");
                continue;
            }
            DraftBomEntries.addVersion(
                    versions,
                    GradleInspectionMapper.coordinateOf(resolved),
                    GradleInspectionMapper.versionOf(resolved),
                    Optional.empty(),
                    notes);
        }

        // A BOM's own Java release and main class are rejected by the authored model (design §12.6),
        // so identity carries only name, group, and version.
        AuthoredManifest manifest = DraftManifests.project(
                DraftManifests.identity(
                        primary.name(),
                        Optional.of(GradleInspectionMapper.emittedGroup(primary)),
                        Optional.of(GradleInspectionMapper.emittedVersion(primary)),
                        Optional.empty(),
                        notes),
                DraftManifests.metadata(Optional.empty(), notes),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                DraftBomEntries.packaging(imports, versions));
        GradleInspectionMapper.addCoordinatePlaceholderNotes(primary, notes);
        notes.add("Drafted a [bom] member from a Gradle java-platform project: platform() imports became"
                + " [bom.imports] and constraints became [bom.versions]. Review the pins and set members if"
                + " this BOM should manage a Zolt workspace family.");
        return new DraftZoltToml(manifest, notes);
    }

    private static void notePlainDependency(GradleDependencyInspection dependency, List<String> notes) {
        String resolved = dependency.resolvedCoordinate();
        String coordinate = resolved == null || resolved.isBlank()
                ? dependency.notation()
                : GradleInspectionMapper.coordinateOf(resolved);
        notes.add("Gradle java-platform project declares dependency `" + coordinate + "` in `"
                + dependency.configuration() + "`; a Zolt BOM publishes only a curated version set and"
                + " carries no dependencies. Move it to the consuming module, or pin it under [bom.versions].");
    }
}
