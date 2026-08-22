package sh.zolt.toml.manifest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.toml.manifest.edit.ManifestSourceEditor;

final class ManifestMutationEditorIntegrationTest {
    private static final String PROJECT = "[project]\nname = \"demo\"\n";
    private final ZoltManifestParser parser = new ZoltManifestParser();
    private final ManifestSourceEditor editor = new ManifestSourceEditor();

    @Test
    void mapMutationsRoundTripWithoutRegeneratingUnrelatedSource() {
        String source = PROJECT + """

                [versions]
                library  = "1.0.0" # retained

                [platforms]
                "org.example:old-platform" = "1.0.0"

                [dependencies.constraints]
                "org.example:library" = "1.0.0"
                """;
        AuthoredManifest requested = parser.parse(source).authored();
        requested = AuthoredManifestMutator.setVersionAlias(
                requested, new LocalId("library"), new VersionAliasValue("2.0.0"));
        requested = AuthoredManifestMutator.removePlatform(
                requested, coordinate("org.example:old-platform"));
        requested = AuthoredManifestMutator.setPlatform(
                requested,
                coordinate("org.example:new-platform"),
                new PlatformSelector.VersionReference(new LocalId("library")));
        requested = AuthoredManifestMutator.setDependencyConstraint(
                requested,
                coordinate("org.example:library"),
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.VersionReference(new LocalId("library")),
                        Optional.of("shared release")));

        ZoltManifestDocument edited = editor.edit(parser.parse(source), requested);

        assertEquals(requested, edited.authored());
        assertTrue(edited.source().contains("library  = \"2.0.0\" # retained"));
        assertTrue(edited.source().contains(
                "\"org.example:new-platform\" = { versionRef = \"library\" }"));
        assertTrue(edited.source().contains(
                "{ versionRef = \"library\", reason = \"shared release\" }"));
        assertTrue(edited.source().contains("[platforms]\n\"org.example:new-platform\""));
    }

    @Test
    void removingTheLastEntryRoundTripsAsAnExplicitEmptyCollection() {
        String source = PROJECT + """

                [versions]
                # retained
                library = "1.0.0"
                """;
        AuthoredManifest requested = AuthoredManifestMutator.removeVersionAlias(
                parser.parse(source).authored(), new LocalId("library"));

        ZoltManifestDocument edited = editor.edit(parser.parse(source), requested);

        assertEquals(PROJECT + """

                [versions]
                # retained
                """, edited.source());
        assertEquals(requested, edited.authored());
    }

    @Test
    void allEightDependencyLanesRoundTripInFinalOrder() {
        AuthoredManifest requested = parser.parse(PROJECT).authored();
        int index = 0;
        for (DependencyLane lane : DependencyLane.values()) {
            requested = AuthoredManifestMutator.setDependency(
                    requested,
                    dependency(lane, "org.example:library-" + index++));
        }

        ZoltManifestDocument edited = editor.edit(parser.parse(PROJECT), requested);

        assertEquals(requested, edited.authored());
        assertEquals(List.of(
                        DependencyLane.IMPLEMENTATION,
                        DependencyLane.API,
                        DependencyLane.RUNTIME,
                        DependencyLane.PROVIDED,
                        DependencyLane.DEV,
                        DependencyLane.TEST,
                        DependencyLane.PROCESSOR,
                        DependencyLane.TEST_PROCESSOR),
                edited.authored().dependencies().orElseThrow().declarations().stream()
                        .map(AuthoredDependency::lane)
                        .toList());
    }

    @Test
    void anExplicitMoveCarriesItsCommentAndRoundTrips() {
        String source = PROJECT + """

                [dependencies.api]
                "org.example:library" = "1.0.0"  # retained on move

                [dependencies.runtime]
                "org.example:existing" = "1.0.0"
                """;
        AuthoredManifest requested = AuthoredManifestMutator.moveDependency(
                parser.parse(source).authored(),
                DependencyLane.API,
                DependencyLane.RUNTIME,
                coordinate("org.example:library"));

        ZoltManifestDocument edited = editor.edit(parser.parse(source), requested);

        assertEquals(requested, edited.authored());
        assertTrue(edited.source().contains(
                "\"org.example:library\" = \"1.0.0\"  # retained on move"));
    }

    @Test
    void bothBomMapsCanBeCreatedAndRoundTripTogether() {
        AuthoredManifest requested = AuthoredManifestMutator.setBomVersion(
                parser.parse(PROJECT).authored(),
                coordinate("org.example:library"),
                new AuthoredBom.Version(
                        new PlatformSelector.FixedVersion("1.0.0"),
                        Optional.empty(),
                        Optional.empty()));
        requested = AuthoredManifestMutator.setBomImport(
                requested,
                coordinate("org.example:platform-bom"),
                new PlatformSelector.FixedVersion("2.0.0"));

        ZoltManifestDocument edited = editor.edit(parser.parse(PROJECT), requested);

        assertEquals(requested, edited.authored());
        assertTrue(edited.source().contains("[bom.versions]"));
        assertTrue(edited.source().contains("[bom.imports]"));
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate) {
        return new AuthoredDependency(
                lane,
                coordinate(coordinate),
                new DependencySelector.FixedVersion("1.0.0"),
                AuthoredDependencyMetadata.none());
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }
}
