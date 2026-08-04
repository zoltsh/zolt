package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ReleaseDistributionUrlLayoutTest {
    @Test
    void defaultsToMovingSignedMetadataOnly() {
        ReleaseDistributionUrlLayout urls = new ReleaseDistributionUrlLayout();

        assertEquals("https://dist.zolt.sh", urls.origin());
        assertEquals("https://dist.zolt.sh/channels/stable.json", urls.channelManifestUrl("stable"));
        assertEquals("https://dist.zolt.sh/channels/preview.json", urls.channelManifestUrl("preview"));
        assertEquals("https://dist.zolt.sh/channels/zap.json", urls.channelManifestUrl("zap"));
        assertEquals("https://dist.zolt.sh/releases/zap.json", urls.releaseIndexUrl("zap"));
    }

    @Test
    void supportsAnExplicitHttpsMetadataMirror() {
        ReleaseDistributionUrlLayout urls =
                new ReleaseDistributionUrlLayout("https://downloads.example.test/zolt/");

        assertEquals("https://downloads.example.test/zolt", urls.origin());
        assertEquals(
                "https://downloads.example.test/zolt/channels/stable.json",
                urls.channelManifestUrl("stable"));
    }

    @Test
    void rejectsInsecureOriginsAndUnsafeChannelSegments() {
        assertThrows(
                ReleaseChannelManifestException.class,
                () -> new ReleaseDistributionUrlLayout("http://dist.zolt.sh"));
        ReleaseDistributionUrlLayout urls = new ReleaseDistributionUrlLayout();
        assertThrows(
                ReleaseChannelManifestException.class,
                () -> urls.channelManifestUrl("../stable"));
    }
}
