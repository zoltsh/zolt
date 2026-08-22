package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBomVersionsDecoderTest {
    private final ManifestBomVersionsDecoder decoder = new ManifestBomVersionsDecoder();

    @Test
    void preservesOmissionAndExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[bom]\nmembers = true\n").isEmpty());
        assertTrue(decode("[bom.imports]\n").isEmpty());

        Map<DependencyCoordinate, AuthoredBom.Version> versions =
                decode("[bom.versions]\n").orElseThrow();
        assertTrue(versions.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> versions.put(
                        coordinate("org.example:demo"),
                        version(new PlatformSelector.FixedVersion("1.0"))));
    }

    @Test
    void decodesEverySelectorAndCanonicalizesImmutableVersions() {
        Map<DependencyCoordinate, AuthoredBom.Version> versions = decode("""
                [bom.versions]
                "org.example:zeta" = { type = "test-jar", classifier = "tests", versionRef = "release" }
                "com.example:alpha" = "1.0-SNAPSHOT"
                "org.example:middle" = { version = "1.5.0" }
                """).orElseThrow();

        assertEquals(
                List.of(
                        coordinate("com.example:alpha"),
                        coordinate("org.example:middle"),
                        coordinate("org.example:zeta")),
                List.copyOf(versions.keySet()));
        PlatformSelector.FixedVersion alpha = assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                versions.get(coordinate("com.example:alpha")).selector());
        assertEquals("1.0-SNAPSHOT", alpha.value());
        PlatformSelector.FixedVersion middle = assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                versions.get(coordinate("org.example:middle")).selector());
        assertEquals("1.5.0", middle.value());
        AuthoredBom.Version zeta = versions.get(coordinate("org.example:zeta"));
        PlatformSelector.VersionReference reference = assertInstanceOf(
                PlatformSelector.VersionReference.class, zeta.selector());
        assertEquals("release", reference.alias().value());
        assertEquals(Optional.of("tests"), zeta.classifier());
        assertEquals(Optional.of("test-jar"), zeta.type());
        assertThrows(UnsupportedOperationException.class, versions::clear);
    }

    @Test
    void anchorsSelectorFailuresToScalarAndExactNestedMembers() {
        assertSemanticFailure(
                "[bom.versions]\n\"org.example:scalar\" = \"LATEST\"\n",
                "`bom.versions.org.example:scalar`",
                "Invalid platform version");
        assertSemanticFailure(
                "[bom.versions]\n\"org.example:inline\" = { version = \"LATEST\" }\n",
                "`bom.versions.org.example:inline.version`",
                "Invalid platform version");
        assertSemanticFailure(
                "[bom.versions]\n\"org.example:reference\" = { versionRef = \"Bad_Id\" }\n",
                "`bom.versions.org.example:reference.versionRef`",
                "Invalid local ID");
    }

    @Test
    void anchorsVariantFailuresToTheirExactMembers() {
        assertSemanticFailure(
                "[bom.versions]\n\"org.example:demo\" = { version = \"1.0\", classifier = \"bad|classifier\" }\n",
                "`bom.versions.org.example:demo.classifier`",
                "must not contain `|`");
        assertSemanticFailure(
                "[bom.versions]\n\"org.example:demo\" = { version = \"1.0\", type = \"bad|type\" }\n",
                "`bom.versions.org.example:demo.type`",
                "must not contain `|`");
    }

    @Test
    void followsCanonicalClassifierThenTypeOrderDespiteReverseAssignments() {
        assertSemanticFailure(
                """
                [bom.versions]
                "org.example:demo" = { type = "bad|type", classifier = "bad|classifier", version = "LATEST" }
                """,
                "`bom.versions.org.example:demo.version`",
                "Invalid platform version");
        ZoltConfigException failure = assertSemanticFailure(
                """
                [bom.versions]
                "org.example:demo" = { type = "bad|type", classifier = "bad|classifier", version = "1.0" }
                """,
                "`bom.versions.org.example:demo.classifier`",
                "must not contain `|`");
        assertFalse(failure.getMessage().contains(".type`"), failure.getMessage());
    }

    @Test
    void validatesEntriesBeforeCanonicalSorting() {
        assertSemanticFailure(
                """
                [bom.versions]
                "org.example:zeta" = "LATEST"
                "com.example:alpha" = { versionRef = "Bad_Id" }
                """,
                "`bom.versions.org.example:zeta`",
                "Invalid platform version");
    }

    @Test
    void leavesDynamicKeysClosedUnionAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo:tests\" = \"1.0\"\n",
                "Invalid dynamic key `org.example:demo:tests`");
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo\" = {}\n",
                "must not use an empty inline table");
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo\" = { classifier = \"tests\" }\n",
                "must declare exactly one of `version` or `versionRef`");
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo\" = { version = \"1.0\", versionRef = \"release\" }\n",
                "must declare exactly one of `version` or `versionRef`");
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo\" = { value = \"1.0\" }\n",
                "Unknown manifest field `bom.versions.org.example:demo.value`");
        assertShapeFailure(
                "[bom.versions]\n\"org.example:demo\" = 42\n",
                "expected string or inline table but found integer");
        assertShapeFailure(
                "bom.versions = {}\n",
                "must use one physical assignment line under the explicit canonical table header");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<Map<DependencyCoordinate, AuthoredBom.Version>> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private ZoltConfigException assertSemanticFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }

    private static AuthoredBom.Version version(PlatformSelector selector) {
        return new AuthoredBom.Version(selector, Optional.empty(), Optional.empty());
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }
}
