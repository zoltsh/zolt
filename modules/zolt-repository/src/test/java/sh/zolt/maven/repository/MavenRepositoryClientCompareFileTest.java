package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class MavenRepositoryClientCompareFileTest extends MavenRepositoryClientTestSupport {
    private static final String PATH = "com/acme/app/1.0.0/app-1.0.0.jar";

    @Test
    void comparesMatchingPublicationArtifactLargerThanTheSmallDocumentLimit() {
        byte[] body = largeBody((byte) 7);
        responses.put("/maven2/" + PATH, body);

        RemoteFileComparison comparison = client.compareFile(
                baseUri,
                PATH,
                RepositoryAuthentication.none(),
                body.length,
                sha256(body));

        assertEquals(RemoteFileComparison.MATCHING, comparison);
    }

    @Test
    void reportsDifferentForSameLengthDifferentBytes() {
        byte[] expected = largeBody((byte) 7);
        byte[] remote = largeBody((byte) 9);
        responses.put("/maven2/" + PATH, remote);

        RemoteFileComparison comparison = client.compareFile(
                baseUri,
                PATH,
                RepositoryAuthentication.none(),
                expected.length,
                sha256(expected));

        assertEquals(RemoteFileComparison.DIFFERENT, comparison);
    }

    @Test
    void reportsLongerRemoteBodyAsDifferent() {
        byte[] expected = new byte[1024];
        responses.put("/maven2/" + PATH, new byte[expected.length + 1]);

        RemoteFileComparison comparison = client.compareFile(
                baseUri,
                PATH,
                RepositoryAuthentication.none(),
                expected.length,
                sha256(expected));

        assertEquals(RemoteFileComparison.DIFFERENT, comparison);
    }

    @Test
    void reportsAbsentWithoutUploading() {
        RemoteFileComparison comparison = client.compareFile(
                baseUri,
                PATH,
                RepositoryAuthentication.none(),
                12,
                sha256(new byte[12]));

        assertEquals(RemoteFileComparison.ABSENT, comparison);
    }

    private static byte[] largeBody(byte value) {
        byte[] body = new byte[9 * 1024 * 1024];
        Arrays.fill(body, value);
        return body;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
