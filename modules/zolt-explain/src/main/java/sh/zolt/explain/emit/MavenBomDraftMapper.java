package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.explain.maven.MavenProjectInspection;
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
 * Drafts a {@code [bom]} member from a standalone Maven {@code dependencyManagement} POM: import-scope
 * BOMs become {@code [bom.imports]} and plain pins become {@code [bom.versions]}.
 */
final class MavenBomDraftMapper {
    private MavenBomDraftMapper() {
    }

    static boolean isBom(MavenProjectInspection primary) {
        return "pom".equals(primary.packaging())
                && primary.modules().isEmpty()
                && primary.sourceRoots().isEmpty()
                && (!primary.dependencyManagement().isEmpty() || !primary.importedBoms().isEmpty());
    }

    static DraftZoltToml map(MavenProjectInspection primary, List<String> notes) {
        Map<DependencyCoordinate, PlatformSelector> imports = new TreeMap<>();
        for (MavenDependencyInspection bom : primary.importedBoms()) {
            String coordinate = MavenInspectionMapper.coordinateOf(bom.coordinate());
            DraftBomEntries.addImport(imports, coordinate, bom.version(), notes);
        }
        Map<DependencyCoordinate, AuthoredBom.Version> versions = new TreeMap<>();
        for (MavenDependencyInspection pin : primary.dependencyManagement()) {
            String coordinate = MavenInspectionMapper.coordinateOf(pin.coordinate());
            DraftBomEntries.addVersion(
                    versions,
                    coordinate,
                    pin.version(),
                    Optional.of(pin.classifier()).filter(value -> !value.isBlank()),
                    notes);
        }
        // A BOM's own Java release and main class are rejected by the authored model (design §12.6),
        // so identity carries only name, group, and version.
        AuthoredManifest manifest = DraftManifests.project(
                DraftManifests.identity(
                        primary.artifactId(),
                        Optional.of(MavenInspectionMapper.group(primary, notes)),
                        Optional.of(MavenInspectionMapper.version(primary, notes)),
                        Optional.empty(),
                        notes),
                DraftManifests.metadata(Optional.empty(), notes),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                DraftBomEntries.packaging(imports, versions));
        notes.add("Drafted a [bom] member from Maven dependencyManagement: import-scope BOMs became"
                + " [bom.imports] and plain pins became [bom.versions]. Review the pins and set members if this"
                + " BOM should manage a Zolt workspace family.");
        return new DraftZoltToml(manifest, notes);
    }
}
