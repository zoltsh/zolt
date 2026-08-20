package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBomDecoderTest {
    private final ManifestBomDecoder decoder = new ManifestBomDecoder();

    @Test
    void preservesWholeDomainOmission() {
        assertTrue(decode("").isEmpty());
    }

    @Test
    void composesEachIndependentlyAuthoredBomDomainWithoutDefaults() {
        AuthoredBom memberOnly = decode("[bom]\nmembers = true\n").orElseThrow();
        assertTrue(memberOnly.members().isPresent());
        assertTrue(memberOnly.versions().isEmpty());
        assertTrue(memberOnly.imports().isEmpty());

        AuthoredBom versionOnly = decode(
                "[bom.versions]\n\"org.example:demo\" = \"1.0\"\n")
                .orElseThrow();
        assertTrue(versionOnly.members().isEmpty());
        assertEquals(
                List.of(coordinate("org.example:demo")),
                List.copyOf(versionOnly.versions().orElseThrow().keySet()));
        assertTrue(versionOnly.imports().isEmpty());

        AuthoredBom importOnly = decode(
                "[bom.imports]\n\"org.example:demo-bom\" = { versionRef = \"release\" }\n")
                .orElseThrow();
        assertTrue(importOnly.members().isEmpty());
        assertTrue(importOnly.versions().isEmpty());
        assertInstanceOf(
                PlatformSelector.VersionReference.class,
                importOnly.imports().orElseThrow().get(coordinate("org.example:demo-bom")));
    }

    @Test
    void retainsExplicitEmptyCollectionsAsBomPresence() {
        AuthoredBom emptyVersions = decode("[bom.versions]\n").orElseThrow();
        assertEquals(Optional.of(Map.of()), emptyVersions.versions());
        assertTrue(emptyVersions.imports().isEmpty());

        AuthoredBom emptyImports = decode("[bom.imports]\n").orElseThrow();
        assertTrue(emptyImports.versions().isEmpty());
        assertEquals(Optional.of(Map.of()), emptyImports.imports());

        AuthoredBom both = decode("[bom.versions]\n[bom.imports]\n").orElseThrow();
        assertEquals(Optional.of(Map.of()), both.versions());
        assertEquals(Optional.of(Map.of()), both.imports());
        assertTrue(both.members().isEmpty());
    }

    @Test
    void composesAllDomainsWithCanonicalImmutableChildren() {
        AuthoredBom bom = decode("""
                [bom]
                exclude = ["modules/zeta", "apps/admin"]
                members = true

                [bom.versions]
                "org.example:zeta" = "2.0"
                "com.example:alpha" = { versionRef = "release", classifier = "tests" }

                [bom.imports]
                "org.example:zeta-bom" = { versionRef = "platform" }
                "com.example:alpha-bom" = "1.0"
                """).orElseThrow();

        AuthoredBom.Members members = bom.members().orElseThrow();
        assertInstanceOf(AuthoredBom.AllMembers.class, members.selection());
        assertEquals(
                List.of(path("apps/admin"), path("modules/zeta")),
                members.exclude());
        assertEquals(
                List.of(coordinate("com.example:alpha"), coordinate("org.example:zeta")),
                List.copyOf(bom.versions().orElseThrow().keySet()));
        assertEquals(
                List.of(
                        coordinate("com.example:alpha-bom"),
                        coordinate("org.example:zeta-bom")),
                List.copyOf(bom.imports().orElseThrow().keySet()));
        PlatformSelector.VersionReference reference = assertInstanceOf(
                PlatformSelector.VersionReference.class,
                bom.imports().orElseThrow().get(coordinate("org.example:zeta-bom")));
        assertEquals("platform", reference.alias().value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> bom.versions().orElseThrow().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> bom.imports().orElseThrow().clear());
    }

    @Test
    void followsMembersThenVersionsThenImportsRegardlessSourceOrder() {
        assertFailure(
                """
                [bom.imports]
                "org.example:demo-bom" = "LATEST"
                [bom.versions]
                "org.example:demo" = "LATEST"
                [bom]
                members = false
                """,
                "`bom.members`");
        assertFailure(
                """
                [bom.imports]
                "org.example:demo-bom" = "LATEST"
                [bom.versions]
                "org.example:demo" = "LATEST"
                """,
                "`bom.versions.org.example:demo`");
        assertFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = \"LATEST\"\n",
                "`bom.imports.org.example:demo-bom`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredBom> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private void assertFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }
}
