package sh.zolt.release.signing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReleaseSignatureVerifierTest {
    @Test
    void bundlesSeparateZapAndPreviewTrustKeys() {
        assertEquals(
                List.of("zolt-release-2026", "zolt-preview-2026"),
                ReleaseTrustedKeys.bundled().stream()
                        .map(ReleaseSigningKey::keyId)
                        .toList());
    }

    @Test
    void verifiesEd25519SidecarWithTrustedKey() throws GeneralSecurityException {
        SignatureFixture fixture = SignatureFixture.create("test-release-key");
        byte[] payload = "release-channel-json".getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> fixture.verifier().verify(payload, fixture.sidecar(payload)));
    }

    @Test
    void rejectsTamperedPayload() throws GeneralSecurityException {
        SignatureFixture fixture = SignatureFixture.create("test-release-key");
        byte[] payload = "release-channel-json".getBytes(StandardCharsets.UTF_8);

        ReleaseSignatureException exception = assertThrows(
                ReleaseSignatureException.class,
                () -> fixture.verifier().verify("tampered".getBytes(StandardCharsets.UTF_8), fixture.sidecar(payload)));

        assertTrue(exception.getMessage().contains("invalid"), exception.getMessage());
    }

    @Test
    void rejectsUntrustedKeyIds() throws GeneralSecurityException {
        SignatureFixture fixture = SignatureFixture.create("trusted-key");
        byte[] payload = "release-channel-json".getBytes(StandardCharsets.UTF_8);
        String sidecar = fixture.sidecar(payload).replace("trusted-key", "other-key");

        ReleaseSignatureException exception = assertThrows(
                ReleaseSignatureException.class,
                () -> fixture.verifier().verify(payload, sidecar));

        assertTrue(exception.getMessage().contains("untrusted key `other-key`"), exception.getMessage());
    }

    @Test
    void rejectsMalformedSidecars() {
        ReleaseSignatureVerifier verifier = new ReleaseSignatureVerifier(List.of());

        assertThrows(ReleaseSignatureException.class, () -> verifier.verify(new byte[0], ""));
        assertThrows(ReleaseSignatureException.class, () -> verifier.verify(new byte[0], "version: nope\nkeyId: key\nsignature: AA==\n"));
        assertThrows(ReleaseSignatureException.class, () -> verifier.verify(new byte[0], "version: zolt-ed25519-v1\nkeyId: key\nsignature: not base64\n"));
    }

    private record SignatureFixture(ReleaseSignatureVerifier verifier, KeyPair keyPair, String keyId) {
        static SignatureFixture create(String keyId) throws GeneralSecurityException {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            ReleaseSigningKey signingKey = new ReleaseSigningKey(keyId, "Ed25519", publicKey);
            return new SignatureFixture(new ReleaseSignatureVerifier(List.of(signingKey)), keyPair, keyId);
        }

        String sidecar(byte[] payload) throws GeneralSecurityException {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keyPair.getPrivate());
            signature.update(payload);
            return """
                    version: zolt-ed25519-v1
                    keyId: %s
                    signature: %s
                    """.formatted(keyId, Base64.getEncoder().encodeToString(signature.sign()));
        }
    }
}
