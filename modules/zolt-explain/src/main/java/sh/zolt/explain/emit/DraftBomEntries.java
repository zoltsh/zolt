package sh.zolt.explain.emit;

import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredPackaging;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared {@code [bom]} drafting for the Maven and Gradle BOM mappers.
 *
 * <p>{@code bom.members} is deliberately left unauthored: a migration audit cannot know which Zolt
 * workspace members a published BOM should manage, and guessing would publish the wrong version set.
 * The mappers add a review note pointing at that decision instead.
 */
final class DraftBomEntries {
    private DraftBomEntries() {
    }

    static void addImport(
            Map<DependencyCoordinate, PlatformSelector> imports,
            String coordinate,
            String version,
            List<String> notes) {
        if (unusableVersion(coordinate, version, "[bom.imports]", notes)) {
            return;
        }
        try {
            imports.put(
                    new DependencyCoordinate(coordinate),
                    new PlatformSelector.FixedVersion(version));
        } catch (IllegalArgumentException exception) {
            notes.add("BOM import `" + coordinate + "` could not be expressed: " + exception.getMessage()
                    + " Add it under [bom.imports] by hand.");
        }
    }

    static void addVersion(
            Map<DependencyCoordinate, AuthoredBom.Version> versions,
            String coordinate,
            String version,
            Optional<String> classifier,
            List<String> notes) {
        if (unusableVersion(coordinate, version, "[bom.versions]", notes)) {
            return;
        }
        try {
            versions.put(
                    new DependencyCoordinate(coordinate),
                    new AuthoredBom.Version(
                            new PlatformSelector.FixedVersion(version), classifier, Optional.empty()));
        } catch (IllegalArgumentException exception) {
            notes.add("BOM version `" + coordinate + "` could not be expressed: " + exception.getMessage()
                    + " Add it under [bom.versions] by hand.");
        }
    }

    /**
     * The drafted packaging. An audit whose every entry was unusable produces no {@code [bom]} domain,
     * because the authored model requires a BOM to carry at least one version, import, or member set.
     */
    static AuthoredPackaging packaging(
            Map<DependencyCoordinate, PlatformSelector> imports,
            Map<DependencyCoordinate, AuthoredBom.Version> versions) {
        if (imports.isEmpty() && versions.isEmpty()) {
            return AuthoredPackaging.empty();
        }
        return new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(),
                        versions.isEmpty() ? Optional.empty() : Optional.of(versions),
                        imports.isEmpty() ? Optional.empty() : Optional.of(imports))));
    }

    private static boolean unusableVersion(
            String coordinate, String version, String section, List<String> notes) {
        if (version == null || version.isBlank() || version.contains("${")) {
            notes.add("BOM entry `" + coordinate + "` has an unresolved version `" + version
                    + "`; add it under " + section + " with a fixed version before publishing.");
            return true;
        }
        return false;
    }
}
