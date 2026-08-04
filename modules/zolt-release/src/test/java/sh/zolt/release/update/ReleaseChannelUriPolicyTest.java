package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class ReleaseChannelUriPolicyTest {
    @Test
    void acceptsHttpsWithHostAndLocalFileWhenExplicitlyAllowed() {
        ReleaseChannelUriPolicy.validate(URI.create("https://dist.zolt.sh/channels/zap.json"), false);
        ReleaseChannelUriPolicy.validate(URI.create("file:///tmp/zolt-channel.json"), true);
        ReleaseChannelUriPolicy.requireChannelDocument(
                URI.create("https://dist.zolt.sh/channels/zap.json"), "channels", "zap");
        ReleaseChannelUriPolicy.requireChannelDocument(
                URI.create("https://mirror.example.test/zolt/releases/preview.json"),
                "releases",
                "preview");
        ReleaseChannelUriPolicy.requireChannelDocument(
                URI.create("https://github.com/zoltsh/releases/releases/download/zolt-zap-1/channel-zap.json"),
                "channels",
                "zap");
        ReleaseChannelUriPolicy.requireChannelDocument(
                URI.create("https://github.com/zoltsh/releases/releases/download/zolt-zap-1/release-index-zap.json"),
                "releases",
                "zap");

        assertTrue(ReleaseChannelUriPolicy.isLocalFile(URI.create("file:///tmp/zolt-channel.json")));
        assertFalse(ReleaseChannelUriPolicy.isLocalFile(URI.create("https://dist.zolt.sh/channels/zap.json")));
    }

    @Test
    void rejectsMissingSchemeCredentialsAndNonHttpsSchemes() {
        assertInvalid("dist.zolt.sh/channels/zap.json", "must use HTTPS");
        assertInvalid("https://user:pass@dist.zolt.sh/channels/zap.json", "must not include URL credentials");
        assertInvalid("http://dist.zolt.sh/channels/zap.json", "must be an HTTPS URL with a host");
        assertInvalid("https:///channels/zap.json", "must be an HTTPS URL with a host");
        assertInvalid("https://dist.zolt.sh/channels/zap.json?old=true", "must not include a query");
        assertInvalid("https://dist.zolt.sh/channels/zap.json#fragment", "must not include a query");
    }

    @Test
    void rejectsChannelDocumentsThatDoNotMatchTheirSignedChannel() {
        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> ReleaseChannelUriPolicy.requireChannelDocument(
                        URI.create("https://dist.zolt.sh/channels/stable.json"),
                        "channels",
                        "zap"));

        assertTrue(exception.getMessage().contains("/channels/zap.json"));
    }

    @Test
    void rejectsLocalFileWhenDisallowedOrNotLocalPath() {
        NativeUpdateException disallowed = assertThrows(
                NativeUpdateException.class,
                () -> ReleaseChannelUriPolicy.validate(URI.create("file:///tmp/zolt-channel.json"), false));
        NativeUpdateException authority = assertThrows(
                NativeUpdateException.class,
                () -> ReleaseChannelUriPolicy.validate(URI.create("file://dist.zolt.sh/channel.json"), true));

        assertTrue(disallowed.getMessage().contains("may use file: only"));
        assertTrue(authority.getMessage().contains("without an authority"));
    }

    private static void assertInvalid(String uri, String message) {
        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> ReleaseChannelUriPolicy.validate(URI.create(uri), false));

        assertTrue(exception.getMessage().contains(message), exception.getMessage());
    }
}
