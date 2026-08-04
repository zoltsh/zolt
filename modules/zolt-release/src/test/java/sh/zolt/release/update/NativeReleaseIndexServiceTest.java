package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeReleaseIndexServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void listsReleasesFromLocalDevelopmentIndexWithoutSignature() throws IOException {
        Path index = tempDir.resolve("zap.json");
        Files.writeString(index, indexJson());
        NativeReleaseIndexService service = new NativeReleaseIndexService();

        NativeReleaseListResult result = service.list(new NativeReleaseListRequest(index.toUri()));

        assertEquals("zap", result.channel());
        assertEquals("0.1.0-zap.20260707.333333333333", result.releases().getFirst().version());
        assertEquals("2026-07-07T00:00:00Z", result.releases().getFirst().createdAt());
        assertEquals("0123456789abcdef0123456789abcdef01234567", result.releases().getFirst().commit());
        assertEquals(List.of(ReleaseTarget.LINUX_X64), result.releases().getFirst().targets());
    }

    @Test
    void remoteReleaseIndexRequiresSignatureSidecar() {
        URI indexUri = URI.create("https://dist.zolt.sh/releases/zap.json");
        NativeReleaseIndexService service = new NativeReleaseIndexService(
                new sh.zolt.release.channel.ReleaseIndexManifestValidator(),
                sh.zolt.release.signing.ReleaseSignatureVerifier.bundled(),
                new ByteMapTransport(Map.of(indexUri, indexJson().getBytes(StandardCharsets.UTF_8))));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.list(new NativeReleaseListRequest(indexUri)));

        assertTrue(exception.getMessage().contains("Release index signature is required"));
        assertTrue(exception.getMessage().contains("https://dist.zolt.sh/releases/zap.json.sig"));
    }

    @Test
    void remoteReleaseIndexRejectsInvalidSignature() {
        URI indexUri = URI.create("https://dist.zolt.sh/releases/zap.json");
        URI signatureUri = URI.create("https://dist.zolt.sh/releases/zap.json.sig");
        NativeReleaseIndexService service = new NativeReleaseIndexService(
                new sh.zolt.release.channel.ReleaseIndexManifestValidator(),
                sh.zolt.release.signing.ReleaseSignatureVerifier.bundled(),
                new ByteMapTransport(Map.of(
                        indexUri, indexJson().getBytes(StandardCharsets.UTF_8),
                        signatureUri, """
                                version: zolt-ed25519-v1
                                keyId: zolt-release-2026
                                signature: invalid
                                """.getBytes(StandardCharsets.UTF_8))));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.list(new NativeReleaseListRequest(indexUri)));

        assertTrue(exception.getMessage().contains("Release index signature verification failed"));
    }

    private static String indexJson() {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "updatedAt": "2026-07-07T00:00:00Z",
                  "versions": [
                    {
                      "version": "0.1.0-zap.20260707.333333333333",
                      "commit": "0123456789abcdef0123456789abcdef01234567",
                      "createdAt": "2026-07-07T00:00:00Z",
                      "artifacts": [
                        {
                          "target": "linux-x64",
                          "archive": "zolt-0.1.0-zap.20260707.333333333333-linux-x64.tar.gz",
                          "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-0.1.0-zap.20260707.333333333333/zolt-0.1.0-zap.20260707.333333333333-linux-x64.tar.gz",
                          "checksumUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-0.1.0-zap.20260707.333333333333/zolt-0.1.0-zap.20260707.333333333333-linux-x64.tar.gz.sha256",
                          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private record ByteMapTransport(Map<URI, byte[]> values) implements NativeUpdateTransport {
        @Override
        public byte[] downloadBytes(URI uri, int maxBytes, String description) throws IOException {
            byte[] value = values.get(uri);
            if (value == null) {
                throw new IOException("missing " + uri);
            }
            return value;
        }

        @Override
        public void download(URI uri, Path output) {
            throw new UnsupportedOperationException("download is not used by release index tests");
        }
    }
}
