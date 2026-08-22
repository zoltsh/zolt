package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import sh.zolt.toml.ZoltConfigException;

final class ManifestBomImportsDecoderTest {
    private final ManifestBomImportsDecoder decoder = new ManifestBomImportsDecoder();

    @Test
    void preservesOmissionAndExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[bom]\nmembers = true\n").isEmpty());
        assertTrue(decode("[bom.versions]\n").isEmpty());

        Map<DependencyCoordinate, PlatformSelector> imports =
                decode("[bom.imports]\n").orElseThrow();
        assertTrue(imports.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> imports.put(
                        coordinate("org.example:demo-bom"),
                        new PlatformSelector.FixedVersion("1.0")));
    }

    @Test
    void decodesEverySelectorAndCanonicalizesImmutableImports() {
        Map<DependencyCoordinate, PlatformSelector> imports = decode("""
                [bom.imports]
                "org.example:zeta-bom" = { versionRef = "release" }
                "com.example:alpha-bom" = "1.0-SNAPSHOT"
                "org.example:middle-bom" = { version = "1.5.0" }
                """).orElseThrow();

        assertEquals(
                List.of(
                        coordinate("com.example:alpha-bom"),
                        coordinate("org.example:middle-bom"),
                        coordinate("org.example:zeta-bom")),
                List.copyOf(imports.keySet()));
        PlatformSelector.FixedVersion alpha = assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                imports.get(coordinate("com.example:alpha-bom")));
        assertEquals("1.0-SNAPSHOT", alpha.value());
        PlatformSelector.FixedVersion middle = assertInstanceOf(
                PlatformSelector.FixedVersion.class,
                imports.get(coordinate("org.example:middle-bom")));
        assertEquals("1.5.0", middle.value());
        PlatformSelector.VersionReference reference = assertInstanceOf(
                PlatformSelector.VersionReference.class,
                imports.get(coordinate("org.example:zeta-bom")));
        assertEquals("release", reference.alias().value());
        assertThrows(UnsupportedOperationException.class, imports::clear);
    }

    @Test
    void anchorsSelectorFailuresToScalarAndExactNestedMembers() {
        assertSemanticFailure(
                "[bom.imports]\n\"org.example:scalar-bom\" = \"LATEST\"\n",
                "`bom.imports.org.example:scalar-bom`",
                "Invalid platform version");
        assertSemanticFailure(
                "[bom.imports]\n\"org.example:inline-bom\" = { version = \"LATEST\" }\n",
                "`bom.imports.org.example:inline-bom.version`",
                "Invalid platform version");
        assertSemanticFailure(
                "[bom.imports]\n\"org.example:reference-bom\" = { versionRef = \"Bad_Id\" }\n",
                "`bom.imports.org.example:reference-bom.versionRef`",
                "Invalid local ID");
    }

    @Test
    void validatesEntriesBeforeCanonicalSorting() {
        assertSemanticFailure(
                """
                [bom.imports]
                "org.example:zeta-bom" = "LATEST"
                "com.example:alpha-bom" = { versionRef = "Bad_Id" }
                """,
                "`bom.imports.org.example:zeta-bom`",
                "Invalid platform version");
    }

    @Test
    void leavesDynamicKeysClosedUnionMetadataAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo:bom\" = \"1.0\"\n",
                "Invalid dynamic key `org.example:demo:bom`");
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = {}\n",
                "must not use an empty inline table");
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = { version = \"1.0\", versionRef = \"release\" }\n",
                "must declare exactly one of `version` or `versionRef`");
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = { version = \"1.0\", classifier = \"tests\" }\n",
                "Unknown manifest field `bom.imports.org.example:demo-bom.classifier`");
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = { version = \"1.0\", type = \"pom\" }\n",
                "Unknown manifest field `bom.imports.org.example:demo-bom.type`");
        assertShapeFailure(
                "[bom.imports]\n\"org.example:demo-bom\" = 42\n",
                "expected string or inline table but found integer");
        assertShapeFailure(
                "bom.imports = {}\n",
                "must use one physical assignment line under the explicit canonical table header");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<Map<DependencyCoordinate, PlatformSelector>> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private void assertSemanticFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }
}
