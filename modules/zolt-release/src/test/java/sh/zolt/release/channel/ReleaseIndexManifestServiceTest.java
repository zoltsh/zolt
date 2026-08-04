package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ReleaseIndexManifestServiceTest {
    private final ReleaseChannelManifestValidator channelValidator = new ReleaseChannelManifestValidator();
    private final ReleaseIndexManifestValidator indexValidator = new ReleaseIndexManifestValidator();
    private final ReleaseIndexManifestService service = new ReleaseIndexManifestService();

    @Test
    void mergePrependsCurrentChannelAndPreservesPreviousHistory() {
        ReleaseChannelManifest current = channel("0.1.0-zap.20260707.333333333333");
        ReleaseIndexManifest previous = indexValidator.validate(ReleaseIndexManifestValidatorTest.indexJson(
                ReleaseIndexManifestValidatorTest.versionJson("0.1.0-zap.20260706.222222222222"),
                ReleaseIndexManifestValidatorTest.versionJson("0.1.0-zap.20260705.111111111111")));

        ReleaseIndexManifest merged = service.merge(current, Optional.of(previous), 10);

        assertEquals("zap", merged.channel());
        assertEquals(current.createdAt(), merged.updatedAt());
        assertEquals(
                "0.1.0-zap.20260707.333333333333",
                merged.versions().getFirst().version());
        assertEquals(
                "0.1.0-zap.20260705.111111111111",
                merged.versions().get(2).version());
    }

    @Test
    void mergeDeduplicatesCurrentVersionAndHonorsLimit() {
        ReleaseChannelManifest current = channel("0.1.0-zap.20260706.222222222222");
        ReleaseIndexManifest previous = indexValidator.validate(ReleaseIndexManifestValidatorTest.indexJson(
                ReleaseIndexManifestValidatorTest.versionJson("0.1.0-zap.20260706.222222222222"),
                ReleaseIndexManifestValidatorTest.versionJson("0.1.0-zap.20260705.111111111111")));

        ReleaseIndexManifest merged = service.merge(current, Optional.of(previous), 1);

        assertEquals(1, merged.versions().size());
        assertEquals("0.1.0-zap.20260706.222222222222", merged.versions().getFirst().version());
    }

    @Test
    void mergeRejectsMismatchedChannels() {
        ReleaseChannelManifest current = channel("0.1.0-zap.20260707.333333333333");
        ReleaseIndexManifest previewIndex = new ReleaseIndexManifest(
                1,
                "preview",
                "2026-07-06T20:00:00Z",
                indexValidator.validate(ReleaseIndexManifestValidatorTest.indexJson(
                        ReleaseIndexManifestValidatorTest.versionJson("0.1.0-zap.20260706.222222222222"))).versions());

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> service.merge(current, Optional.of(previewIndex), 10));

        assertEquals(
                "Release index channel `preview` does not match release channel `zap`.",
                exception.getMessage());
    }

    @Test
    void mergeRejectsNonPositiveLimit() {
        ReleaseChannelManifest current = channel("0.1.0-zap.20260707.333333333333");

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> service.merge(current, Optional.empty(), 0));

        assertEquals("Release index limit must be at least 1.", exception.getMessage());
    }

    private ReleaseChannelManifest channel(String version) {
        return channelValidator.validate("""
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-07-07T00:00:00Z",
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
                """.formatted(version, version, version, version, version, version));
    }
}
