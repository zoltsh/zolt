package sh.zolt.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Unicode17DataIdentityTest {
    @Test
    void unicodePortabilityDataVersionAndChecksumsAreFrozen() throws IOException {
        assertEquals("unicode-17.0.0-nfc-full-default-case-fold", Unicode17DataIdentity.IDENTITY);
        assertEquals("17.0.0", Unicode17DataIdentity.UNICODE_VERSION);
        assertEquals(1, Unicode17DataIdentity.FORMAT_VERSION);
        assertEquals(
                Unicode17DataIdentity.GENERATED_TABLE_SHA256,
                sha256(resource("unicode-17.0.0-portability.bin")));
        assertEquals(
                Unicode17DataIdentity.GENERATED_CONFORMANCE_SHA256,
                sha256(resource("unicode-17.0.0-nfc-conformance.bin")));
    }

    @Test
    void implementationHasNoHostUnicodeNormalizationOrCaseConversionDependency() throws IOException {
        for (Class<?> implementation : List.of(
                Unicode17Portability.class,
                Unicode17Normalization.class,
                Unicode17Tables.class,
                UnicodeScalarSequence.class)) {
            byte[] bytecode = classBytes(implementation);
            String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);
            assertFalse(constantPool.contains("java/text/Normalizer"), implementation.getName());
            assertFalse(constantPool.contains("toLowerCase"), implementation.getName());
            assertFalse(constantPool.contains("toUpperCase"), implementation.getName());
        }
    }

    @Test
    void nativeImageMetadataIncludesTheFrozenRuntimeTable() throws IOException {
        String config = new String(
                resource("/META-INF/native-image/sh.zolt/zolt-model/resource-config.json"),
                StandardCharsets.UTF_8);

        assertTrue(
                config.contains("unicode-17.0.0-portability.bin"),
                "The native CLI must retain the portability table resource.");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = Unicode17DataIdentityTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String name = type.getSimpleName() + ".class";
        try (InputStream input = type.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
