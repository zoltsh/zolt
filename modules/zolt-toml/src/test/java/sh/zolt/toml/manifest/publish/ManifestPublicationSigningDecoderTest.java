package sh.zolt.toml.manifest.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeSigning;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeSigningWithNullIndex;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPublicationSigningDecoderTest {
    @Test
    void preservesOmissionAndDecodesTheMinimalSigningMethod() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("publish.release = \"releases\"\n").isEmpty());

        AuthoredPublicationSigning signing = decode("""
                [publish.signing]
                method = "gpg"
                """).orElseThrow();
        assertEquals(AuthoredPublicationSigning.Method.GPG, signing.method());
        assertTrue(signing.keyId().isEmpty());
        assertTrue(signing.passphraseEnvironment().isEmpty());
    }

    @Test
    void decodesAllFieldsAndRetainsEachOptionalFormWithoutDefaults() {
        AuthoredPublicationSigning signing = decode("""
                [publish.signing]
                passphraseEnv = "ZOLT_GPG_PASSPHRASE"
                keyId = "0xA1B2C3D4"
                method = "gpg"
                """).orElseThrow();
        assertEquals(AuthoredPublicationSigning.Method.GPG, signing.method());
        assertEquals(Optional.of("0xA1B2C3D4"), signing.keyId());
        assertEquals(
                Optional.of(new EnvironmentVariableName("ZOLT_GPG_PASSPHRASE")),
                signing.passphraseEnvironment());

        AuthoredPublicationSigning keyOnly =
                decode("publish.signing.method = \"gpg\"\npublish.signing.keyId = \"key\"\n")
                        .orElseThrow();
        assertEquals(Optional.of("key"), keyOnly.keyId());
        assertTrue(keyOnly.passphraseEnvironment().isEmpty());

        AuthoredPublicationSigning passphraseOnly = decode("""
                [publish.signing]
                method = "gpg"
                passphraseEnv = "PASSPHRASE"
                """).orElseThrow();
        assertTrue(passphraseOnly.keyId().isEmpty());
        assertEquals(
                Optional.of(new EnvironmentVariableName("PASSPHRASE")),
                passphraseOnly.passphraseEnvironment());
    }

    @Test
    void anchorsBlankAndControlKeyIdsToTheExactField() {
        assertKeyFailure(" ", "must not be blank");
        assertKeyFailure("key\\t", "must not contain NUL or control characters");
    }

    @Test
    void requiresMethodBeforeOptionalFieldsRegardlessAssignmentOrder() {
        for (String source : List.of(
                "publish.signing.keyId = \"key\"\n",
                "publish.signing.passphraseEnv = \"PASSPHRASE\"\n")) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class,
                    () -> decode(source));
            assertTrue(
                    failure.getMessage().contains("publish.signing.method"),
                    failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void leavesEmptyTablesSymbolsEnvironmentNamesKindsAndLegacyFieldsToShapeValidation() {
        assertShapeFailure("[publish.signing]\n", "must not be empty");
        assertShapeFailure("publish.signing.method = \"pgp\"\n", "Invalid symbol `pgp`");
        assertShapeFailure("""
                [publish.signing]
                method = "gpg"
                passphraseEnv = "bad-name"
                """, "Invalid environment-variable name");
        assertShapeFailure("publish.signing.method = 42\n", "expected string but found integer");
        assertShapeFailure("""
                [publish.signing]
                method = "gpg"
                enabled = true
                """, "Unknown manifest field `publish.signing.enabled`");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodeSigningWithNullIndex());
    }

    private static Optional<AuthoredPublicationSigning> decode(String source) {
        return decodeSigning(source);
    }

    private static void assertKeyFailure(String tomlValue, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("""
                        [publish.signing]
                        passphraseEnv = "PASSPHRASE"
                        keyId = "%s"
                        method = "gpg"
                        """.formatted(tomlValue)));
        assertTrue(
                failure.getMessage().contains("`publish.signing.keyId`"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }
}
