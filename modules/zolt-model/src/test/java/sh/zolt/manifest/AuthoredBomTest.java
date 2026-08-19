package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredBomTest {
    @Test
    void sortsAndCopiesExactAllMemberExclusions() {
        ArrayList<WorkspaceMemberPath> exclusions = new ArrayList<>(List.of(
                path("modules/zeta"), path("."), path("apps/admin")));
        AuthoredBom.Members members = new AuthoredBom.Members(
                new AuthoredBom.AllMembers(), exclusions);
        exclusions.clear();

        assertEquals(
                List.of(path("."), path("apps/admin"), path("modules/zeta")),
                members.exclude());
        assertThrows(UnsupportedOperationException.class, () -> members.exclude().clear());
    }

    @Test
    void sortsExplicitMembersAndRejectsInvalidSelectionShapes() {
        AuthoredBom.ExplicitMembers explicit = new AuthoredBom.ExplicitMembers(List.of(
                path("modules/zeta"), path("apps/api")));

        assertEquals(List.of(path("apps/api"), path("modules/zeta")), explicit.paths());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom.ExplicitMembers(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom.ExplicitMembers(List.of(path("apps/api"), path("apps/api"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom.Members(explicit, List.of(path("modules/legacy"))));
    }

    @Test
    void rejectsUnicodeCaseFoldCollisionsInMemberAndExcludeLists() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom.ExplicitMembers(List.of(
                        path("modules/Stra\u00DFe"), path("modules/STRASSE"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom.Members(
                        new AuthoredBom.AllMembers(),
                        List.of(path("apps/Api"), path("apps/api"))));
    }

    @Test
    void preservesAndSortsBomVersionAndImportSources() {
        LinkedHashMap<DependencyCoordinate, AuthoredBom.Version> versions = new LinkedHashMap<>();
        versions.put(
                coordinate("org.zeta:zeta"),
                version(
                        new PlatformSelector.FixedVersion("2.0-SNAPSHOT"),
                        Optional.of("Linux-X86_64"),
                        Optional.of("test-jar")));
        versions.put(
                coordinate("com.alpha:alpha"),
                version(
                        new PlatformSelector.VersionReference(new LocalId("alpha")),
                        Optional.empty(),
                        Optional.empty()));
        LinkedHashMap<DependencyCoordinate, PlatformSelector> imports = new LinkedHashMap<>();
        imports.put(
                coordinate("org.zeta:zeta-bom"),
                new PlatformSelector.VersionReference(new LocalId("zeta")));
        imports.put(
                coordinate("com.alpha:alpha-bom"),
                new PlatformSelector.FixedVersion("1.0"));

        AuthoredBom bom = new AuthoredBom(
                Optional.empty(), Optional.of(versions), Optional.of(imports));
        versions.clear();
        imports.clear();

        assertEquals(
                List.of(coordinate("com.alpha:alpha"), coordinate("org.zeta:zeta")),
                new ArrayList<>(bom.versions().orElseThrow().keySet()));
        assertEquals(
                List.of(coordinate("com.alpha:alpha-bom"), coordinate("org.zeta:zeta-bom")),
                new ArrayList<>(bom.imports().orElseThrow().keySet()));
        AuthoredBom.Version zeta = bom.versions().orElseThrow().get(coordinate("org.zeta:zeta"));
        assertEquals("Linux-X86_64", zeta.classifier().orElseThrow());
        assertEquals("test-jar", zeta.type().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> bom.versions().orElseThrow().clear());
    }

    @Test
    void distinguishesOmittedAndExplicitEmptyBomCollections() {
        AuthoredBom explicitEmptyVersions = new AuthoredBom(
                Optional.empty(), Optional.of(Map.of()), Optional.empty());

        assertEquals(Optional.of(Map.of()), explicitEmptyVersions.versions());
        assertEquals(Optional.empty(), explicitEmptyVersions.imports());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredBom(Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void rejectsUnsupportedBomSelectorsAndUnsafeVariantMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformSelector.FixedVersion("[1.0,2.0)"));
        assertThrows(
                IllegalArgumentException.class,
                () -> version(
                        new PlatformSelector.FixedVersion("1.0"),
                        Optional.of("linux|x86"),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> version(
                        new PlatformSelector.FixedVersion("1.0"),
                        Optional.empty(),
                        Optional.of("pom/import")));
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static AuthoredBom.Version version(
            PlatformSelector selector,
            Optional<String> classifier,
            Optional<String> type) {
        return new AuthoredBom.Version(selector, classifier, type);
    }
}
