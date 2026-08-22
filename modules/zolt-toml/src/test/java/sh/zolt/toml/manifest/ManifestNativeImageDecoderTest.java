package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.toml.ZoltConfigException;

final class ManifestNativeImageDecoderTest {
    private final ManifestNativeImageDecoder decoder = new ManifestNativeImageDecoder();

    @Test
    void preservesWholeDomainOmissionWithoutDefaults() {
        assertTrue(decode("").isEmpty());
    }

    @Test
    void decodesAllFieldsAndPreservesImmutableArgumentOrder() {
        AuthoredNativeImage nativeImage = decode("""
                [native]
                args = ["--native-image-info", "", "--native-image-info"]
                output = "images/native"
                name = "Images/My Native Binary"
                """).orElseThrow();

        assertEquals(Optional.of("Images/My Native Binary"), nativeImage.name());
        assertEquals(
                Optional.of(new ManifestRelativePath("images/native")),
                nativeImage.output());
        assertEquals(
                Optional.of(List.of("--native-image-info", "", "--native-image-info")),
                nativeImage.args());
        assertThrows(
                UnsupportedOperationException.class,
                () -> nativeImage.args().orElseThrow().clear());
    }

    @Test
    void acceptsEachSingleNondefaultFieldWithoutMaterializingSiblings() {
        AuthoredNativeImage named = decode("native.name = \"application\"\n")
                .orElseThrow();
        assertEquals(Optional.of("application"), named.name());
        assertTrue(named.output().isEmpty());
        assertTrue(named.args().isEmpty());

        AuthoredNativeImage output = decode("native.output = \"images/native\"\n")
                .orElseThrow();
        assertTrue(output.name().isEmpty());
        assertEquals(
                Optional.of(new ManifestRelativePath("images/native")),
                output.output());
        assertTrue(output.args().isEmpty());

        AuthoredNativeImage arguments = decode("native.args = [\"--no-fallback\"]\n")
                .orElseThrow();
        assertTrue(arguments.name().isEmpty());
        assertTrue(arguments.output().isEmpty());
        assertEquals(Optional.of(List.of("--no-fallback")), arguments.args());
    }

    @Test
    void retainsExplicitDefaultsWhenAnotherFieldIsMeaningful() {
        AuthoredNativeImage arguments = decode("""
                [native]
                output = "native"
                args = ["--no-fallback"]
                """).orElseThrow();
        assertEquals(
                Optional.of(new ManifestRelativePath("native")),
                arguments.output());
        assertEquals(Optional.of(List.of("--no-fallback")), arguments.args());

        AuthoredNativeImage named = decode("""
                [native]
                name = "application"
                args = []
                """).orElseThrow();
        assertEquals(Optional.of("application"), named.name());
        assertEquals(Optional.of(List.of()), named.args());
    }

    @Test
    void anchorsDefaultOnlyTablesToTheFirstCanonicalPresentField() {
        assertSemanticFailure(
                "native.output = \"native\"\n",
                "`native.output`",
                "must contain at least one nondefault field");
        assertSemanticFailure(
                "native.args = []\n",
                "`native.args`",
                "must contain at least one nondefault field");

        ZoltConfigException failure = assertSemanticFailure(
                """
                [native]
                args = []
                output = "native"
                """,
                "`native.output`",
                "must contain at least one nondefault field");
        assertFalse(failure.getMessage().contains("`native.args`"), failure.getMessage());
    }

    @Test
    void validatesNameBeforeTheDelayedWholeTableInvariant() {
        ZoltConfigException failure = assertSemanticFailure(
                """
                [native]
                args = []
                output = "native"
                name = " "
                """,
                "`native.name`",
                "Native image name must not be blank.");
        assertFalse(failure.getMessage().contains("`native.output`"), failure.getMessage());
    }

    @Test
    void leavesEmptyTablesInvalidOutputAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[native]\n",
                "Manifest table `[native]` must not be empty");
        assertShapeFailure(
                "native.output = \"../outside\"\n",
                "`native.output`");
        assertShapeFailure(
                "native.args = \"--no-fallback\"\n",
                "expected string array but found string");
        assertShapeFailure(
                "native.imageName = \"application\"\n",
                "Unknown manifest field `native.imageName`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredNativeImage> decode(String source) {
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
}
