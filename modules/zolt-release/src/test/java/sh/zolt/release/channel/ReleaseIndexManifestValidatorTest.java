package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.release.ReleaseTarget;
import org.junit.jupiter.api.Test;

final class ReleaseIndexManifestValidatorTest {
    private final ReleaseIndexManifestValidator validator = new ReleaseIndexManifestValidator();

    @Test
    void validatesReleaseIndexWithOrderedVersionsAndArtifacts() {
        ReleaseIndexManifest manifest = validator.validate(indexJson(
                versionJson("0.1.0-zap.20260706.222222222222"),
                versionJson("0.1.0-zap.20260705.111111111111")));

        assertEquals(1, manifest.schemaVersion());
        assertEquals("zap", manifest.channel());
        assertEquals("2026-07-06T20:00:00Z", manifest.updatedAt());
        assertEquals(
                "0.1.0-zap.20260706.222222222222",
                manifest.versions().getFirst().version());
        assertEquals(
                "zolt-0.1.0-zap.20260706.222222222222-linux-x64.tar.gz",
                manifest.versions().getFirst().artifactFor(ReleaseTarget.LINUX_X64).archive());
    }

    @Test
    void rejectsDuplicateVersions() {
        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(indexJson(
                        versionJson("0.1.0-zap.20260706.222222222222"),
                        versionJson("0.1.0-zap.20260706.222222222222"))));

        assertEquals(
                "Release index manifest repeats version `0.1.0-zap.20260706.222222222222`.",
                exception.getMessage());
    }

    @Test
    void rejectsMissingVersionsArray() {
        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate("""
                        {
                          "schemaVersion": 1,
                          "channel": "zap",
                          "updatedAt": "2026-07-06T20:00:00Z"
                        }
                        """));

        assertEquals("Release index manifest is missing versions array.", exception.getMessage());
    }

    static String indexJson(String... versions) {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "updatedAt": "2026-07-06T20:00:00Z",
                  "versions": [
                %s
                  ]
                }
                """.formatted(String.join(",\n", versions).indent(4));
    }

    static String versionJson(String version) {
        return """
                {
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-07-06T20:00:00Z",
                  "artifacts": [
                    {
                      "target": "linux-x64",
                      "archive": "zolt-%s-linux-x64.tar.gz",
                      "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz",
                      "checksumUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz.sha256",
                      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "format": "tar.gz",
                      "binaryName": "zolt"
                    }
                  ]
                }
                """.formatted(version, version, version, version, version, version);
    }
}
