package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.toml.ZoltConfigException;

final class ManifestSpringBootDecoderTest {
    private final ManifestSpringBootDecoder decoder = new ManifestSpringBootDecoder();

    @Test
    void preservesOmissionWithoutInferringFromPackageMode() {
        assertTrue(decode("").isEmpty());
        for (String mode : List.of("spring-boot", "spring-boot-war")) {
            assertTrue(decode("package.mode = \"" + mode + "\"\n").isEmpty());
        }
    }

    @Test
    void retainsExplicitTrueAndFalseNativeValues() {
        AuthoredSpringBoot enabled = decode("""
                [framework.spring-boot]
                native = true
                """).orElseThrow();
        assertEquals(Optional.of(true), enabled.nativeImage());

        AuthoredSpringBoot disabled = decode(
                "framework = { spring-boot = { native = false } }\n")
                .orElseThrow();
        assertEquals(Optional.of(false), disabled.nativeImage());
    }

    @Test
    void leavesEmptyTablesUnknownFieldsAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[framework.spring-boot]\n",
                "Manifest table `[framework.spring-boot]` must not be empty");
        assertShapeFailure(
                "[framework.spring-boot]\nenabled = true\n",
                "Unknown manifest field `framework.spring-boot.enabled`");
        assertShapeFailure(
                "[framework.spring-boot]\nnative = \"true\"\n",
                "expected boolean but found string");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredSpringBoot> decode(String source) {
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
