package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPackageManifestDecoderTest {
    private final ManifestPackageManifestDecoder decoder =
            new ManifestPackageManifestDecoder();

    @Test
    void preservesOmissionAndExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[package.manifest]\n",
                "package = { manifest = {} }\n")) {
            AuthoredPackageManifest manifest = decode(source).orElseThrow();
            assertTrue(manifest.attributes().isEmpty());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> manifest.attributes().put("Name", "value"));
        }
    }

    @Test
    void preservesValuesAndReturnsCodePointSortedImmutableAttributes() {
        AuthoredPackageManifest manifest = decode("""
                [package.manifest]
                Zeta = "last"
                "Automatic-Module-Name" = "com.example.library"
                Alpha = "first"
                "X.Vendor-Flag" = "enabled"
                """).orElseThrow();

        assertEquals(
                List.of("Alpha", "Automatic-Module-Name", "X.Vendor-Flag", "Zeta"),
                List.copyOf(manifest.attributes().keySet()));
        assertEquals("first", manifest.attributes().get("Alpha"));
        assertEquals("com.example.library", manifest.attributes().get("Automatic-Module-Name"));
        assertEquals("enabled", manifest.attributes().get("X.Vendor-Flag"));
        assertThrows(UnsupportedOperationException.class, manifest.attributes()::clear);
    }

    @Test
    void decodesInlineAttributesBeforeCanonicalSorting() {
        AuthoredPackageManifest manifest = decode(
                "package = { manifest = { Zeta = \"z\", Alpha = \"a\" } }\n")
                .orElseThrow();

        assertEquals(
                Map.of("Alpha", "a", "Zeta", "z"),
                manifest.attributes());
        assertEquals(
                List.of("Alpha", "Zeta"),
                List.copyOf(manifest.attributes().keySet()));
    }

    @Test
    void leavesInvalidAttributeNamesAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[package.manifest]\n\" Bad\" = \"value\"\n",
                "JAR manifest attribute names must be nonblank");
        assertShapeFailure(
                "[package.manifest]\nName = 42\n",
                "expected string but found integer");
    }

    /**
     * Design §12.2 gives attributes JAR manifest spelling and never makes a blank entry meaningful,
     * so both halves of a blank entry are rejected: the name at the shape layer, the value at the
     * model layer with the offending attribute anchored in the diagnostic.
     */
    @Test
    void rejectsBlankAttributeNamesAndBlankAttributeValues() {
        for (String name : List.of("\"\"", "\" \"")) {
            assertShapeFailure(
                    "[package.manifest]\n" + name + " = \"value\"\n",
                    "JAR manifest attribute names must be nonblank");
        }
        for (String value : List.of("\"\"", "\"   \"")) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class,
                    () -> decode("[package.manifest]\n\"X.Vendor-Flag\" = " + value + "\n"),
                    value);
            assertTrue(failure.getMessage().contains("package.manifest"), failure.getMessage());
            assertTrue(
                    failure.getMessage().contains(
                            "Package manifest attribute `X.Vendor-Flag` value must not be blank."),
                    failure.getMessage());
        }
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredPackageManifest> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }
}
