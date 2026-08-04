package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.channel.ReleaseChannelManifestValidator;
import sh.zolt.release.signing.ReleaseSignatureVerifier;
import sh.zolt.release.signing.ReleaseSigningKey;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class NativeUpdateSignatureTest extends NativeUpdateServiceTestCase {
    private static final URI CHANNEL_URI = URI.create("https://dist.example.test/channels/stable.json");

    @Test
    void verifiesSignedRemoteChannelBeforeUpdating() throws Exception {
        InstalledFixture installed = install("0.1.0");
        SignatureFixture signature = SignatureFixture.create("test-release-key");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        FakeTransport transport = signedRemoteTransport(signature, archive, remoteChannel(archive));
        NativeUpdateService signedService = service(signature, transport);

        NativeUpdateResult result = signedService.update(new NativeUpdateRequest(
                installed.installRoot(),
                installed.binLink(),
                CHANNEL_URI,
                ReleaseTarget.LINUX_X64,
                tempDir.resolve("update-work-signed")));

        assertTrue(result.updated());
        assertEquals("0.1.1", result.availableVersion());
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void rejectsRemoteChannelWhenSignatureIsMissing() throws Exception {
        InstalledFixture installed = install("0.1.0");
        SignatureFixture signature = SignatureFixture.create("test-release-key");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        FakeTransport transport = signedRemoteTransport(signature, archive, remoteChannel(archive));
        transport.bytes.remove(signatureUri());
        NativeUpdateService signedService = service(signature, transport);

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> signedService.update(new NativeUpdateRequest(
                        installed.installRoot(),
                        installed.binLink(),
                        CHANNEL_URI,
                        ReleaseTarget.LINUX_X64,
                        tempDir.resolve("update-work-missing-signature"))));

        assertTrue(exception.getMessage().contains("signature is required"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void rejectsRemoteChannelSignedByUnknownKey() throws Exception {
        InstalledFixture installed = install("0.1.0");
        SignatureFixture signature = SignatureFixture.create("test-release-key");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        FakeTransport transport = signedRemoteTransport(signature, archive, remoteChannel(archive));
        transport.bytes.put(signatureUri(), signature.sidecar("other-key", transport.bytes.get(CHANNEL_URI)));
        NativeUpdateService signedService = service(signature, transport);

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> signedService.update(new NativeUpdateRequest(
                        installed.installRoot(),
                        installed.binLink(),
                        CHANNEL_URI,
                        ReleaseTarget.LINUX_X64,
                        tempDir.resolve("update-work-unknown-signature"))));

        assertTrue(exception.getMessage().contains("untrusted key `other-key`"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void rejectsRemoteChannelWhenManifestIsTamperedAfterSigning() throws Exception {
        InstalledFixture installed = install("0.1.0");
        SignatureFixture signature = SignatureFixture.create("test-release-key");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        byte[] originalChannel = remoteChannel(archive);
        FakeTransport transport = signedRemoteTransport(signature, archive, originalChannel);
        transport.bytes.put(CHANNEL_URI, new String(originalChannel, StandardCharsets.UTF_8)
                .replace("\"0.1.1\"", "\"0.1.2\"")
                .getBytes(StandardCharsets.UTF_8));
        NativeUpdateService signedService = service(signature, transport);

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> signedService.update(new NativeUpdateRequest(
                        installed.installRoot(),
                        installed.binLink(),
                        CHANNEL_URI,
                        ReleaseTarget.LINUX_X64,
                        tempDir.resolve("update-work-tampered-signature"))));

        assertTrue(exception.getMessage().contains("signature is invalid"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    private NativeUpdateService service(SignatureFixture signature, NativeUpdateTransport transport) {
        return new NativeUpdateService(
                new ReleaseChannelManifestValidator(),
                signature.verifier(),
                transport);
    }

    private FakeTransport signedRemoteTransport(SignatureFixture signature, Path archive, byte[] channel)
            throws IOException, GeneralSecurityException {
        URI archiveUri = archiveUri(archive);
        URI checksumUri = URI.create(archiveUri + ".sha256");
        FakeTransport transport = new FakeTransport();
        transport.bytes.put(CHANNEL_URI, channel);
        transport.bytes.put(signatureUri(), signature.sidecar(signature.keyId(), channel));
        transport.files.put(archiveUri, archive);
        transport.files.put(checksumUri, archive.resolveSibling(archive.getFileName() + ".sha256"));
        return transport;
    }

    private byte[] remoteChannel(Path archive) throws IOException {
        String archiveName = archive.getFileName().toString();
        URI archiveUri = archiveUri(archive);
        return """
                {
                  "schemaVersion": 1,
                  "channel": "stable",
                  "version": "0.1.1",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-06-28T00:00:00Z",
                  "artifacts": [
                    {
                      "target": "linux-x64",
                      "archive": "%s",
                      "archiveUrl": "%s",
                      "checksumUrl": "%s.sha256",
                      "format": "tar.gz",
                      "binaryName": "zolt"
                    }
                  ]
                }
                """.formatted(archiveName, archiveUri, archiveUri).getBytes(StandardCharsets.UTF_8);
    }

    private static URI archiveUri(Path archive) {
        return URI.create(
                "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.1/"
                        + archive.getFileName());
    }

    private static URI signatureUri() {
        return URI.create(CHANNEL_URI + ".sig");
    }

    private static final class FakeTransport implements NativeUpdateTransport {
        private final Map<URI, byte[]> bytes = new HashMap<>();
        private final Map<URI, Path> files = new HashMap<>();

        @Override
        public byte[] downloadBytes(URI uri, int maxBytes, String description) throws IOException {
            byte[] content = bytes.get(uri);
            if (content == null) {
                throw new IOException("missing fake response for " + uri);
            }
            if (content.length > maxBytes) {
                throw new NativeUpdateException("Downloaded " + description + " is too large.");
            }
            return content;
        }

        @Override
        public void download(URI uri, Path output) throws IOException {
            Path file = files.get(uri);
            if (file == null) {
                throw new IOException("missing fake file for " + uri);
            }
            Files.copy(file, output);
        }
    }

    private record SignatureFixture(ReleaseSignatureVerifier verifier, KeyPair keyPair, String keyId) {
        static SignatureFixture create(String keyId) throws GeneralSecurityException {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            ReleaseSigningKey signingKey = new ReleaseSigningKey(keyId, "Ed25519", publicKey);
            return new SignatureFixture(new ReleaseSignatureVerifier(List.of(signingKey)), keyPair, keyId);
        }

        byte[] sidecar(String sidecarKeyId, byte[] payload) throws GeneralSecurityException {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keyPair.getPrivate());
            signature.update(payload);
            return """
                    version: zolt-ed25519-v1
                    keyId: %s
                    signature: %s
                    """.formatted(sidecarKeyId, Base64.getEncoder().encodeToString(signature.sign()))
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
