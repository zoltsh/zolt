package sh.zolt.release.channel;

import sh.zolt.release.ReleaseTarget;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

final class ReleaseChannelManifestConstraints {
    private static final String CORE_VERSION =
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
    private static final Pattern STABLE_VERSION = Pattern.compile(CORE_VERSION);
    private static final Pattern PREVIEW_VERSION =
            Pattern.compile(CORE_VERSION + "-(alpha|beta|rc)\\.(?:0|[1-9][0-9]*)");
    private static final Pattern ZAP_VERSION =
            Pattern.compile(CORE_VERSION + "-zap\\.[0-9]{8}\\.[0-9a-f]{12}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern ARCHIVE_FILENAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9A-Fa-f]{64}");
    private static final Pattern SIGNATURE_KIND = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("stable", "preview", "zap");

    private ReleaseChannelManifestConstraints() {
    }

    static void validateChannel(String channel) {
        if (!SUPPORTED_CHANNELS.contains(channel)) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest channel must be one of stable, preview, zap; got `" + channel + "`.");
        }
    }

    static void validateVersion(String channel, String version) {
        validateSafeSegment("version", version);
        Pattern expected = switch (channel) {
            case "stable" -> STABLE_VERSION;
            case "preview" -> PREVIEW_VERSION;
            case "zap" -> ZAP_VERSION;
            default -> throw new ReleaseChannelManifestException(
                    "Release channel manifest channel must be validated before its version.");
        };
        if (expected.matcher(version).matches()) {
            return;
        }
        throw new ReleaseChannelManifestException(
                "Release channel manifest version `"
                        + version
                        + "` is not valid for channel `"
                        + channel
                        + "`.");
    }

    static void validateCommit(String commit) {
        if (!COMMIT.matcher(commit).matches()) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest commit must be exactly 40 lowercase hexadecimal characters.");
        }
    }

    static void validateCreatedAt(String createdAt) {
        if (!createdAt.endsWith("Z")) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest createdAt must be a UTC instant ending in Z.");
        }
        try {
            Instant.parse(createdAt);
        } catch (DateTimeParseException exception) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest createdAt must be a UTC instant.", exception);
        }
    }

    static void validateArchiveFilename(ReleaseTarget target, String archive) {
        validateSafeSegment("archive", archive);
        if (!ARCHIVE_FILENAME.matcher(archive).matches()) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `"
                            + target.id()
                            + "` archive must be a filename using letters, digits, dots, underscores, and hyphens.");
        }
    }

    static void validateReleaseLocation(
            String channel, String version, ReleaseChannelArtifact artifact) {
        String expectedArchive = "zolt-" + version + "-" + artifact.target().id()
                + artifact.target().archiveExtension();
        if (!expectedArchive.equals(artifact.archive())) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `"
                            + artifact.target().id()
                            + "` archive must be `"
                            + expectedArchive
                            + "`.");
        }
        String origin = "https://github.com/zoltsh/releases/releases/download/"
                + releaseTag(channel, version)
                + "/";
        requireExactUrl("archiveUrl", artifact.archiveUrl(), origin + expectedArchive);
        artifact.checksumUrl().ifPresent(value ->
                requireExactUrl("checksumUrl", value, origin + expectedArchive + ".sha256"));
        artifact.signature().ifPresent(signature -> requireExactUrl(
                "signature.url",
                signature.url(),
                origin + expectedArchive + "." + signature.kind()));
    }

    static void validateUrl(String field, String value, boolean allowFileUrls) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest " + field + " must be a valid HTTPS URL.");
        }
        if (uri.getUserInfo() != null) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest " + field + " must not include URL credentials.");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest " + field + " must not include a query or fragment.");
        }
        if (allowFileUrls && "file".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest " + field + " must be a valid HTTPS URL.");
        }
    }

    static void validateSha256(ReleaseTarget target, String sha256) {
        if (!SHA256.matcher(sha256).matches()) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `" + target.id() + "` sha256 must be exactly 64 hexadecimal characters.");
        }
    }

    static void validateSignature(ReleaseChannelArtifact.Signature signature, boolean allowFileUrls) {
        if (!SIGNATURE_KIND.matcher(signature.kind()).matches()) {
            throw new ReleaseChannelManifestException(
                    "Release channel signature kind must use letters, digits, dots, underscores, and hyphens.");
        }
        validateUrl("signature.url", signature.url(), allowFileUrls);
    }

    private static String releaseTag(String channel, String version) {
        return switch (channel) {
            case "stable" -> "zolt-v" + version;
            case "preview" -> "zolt-preview-v" + version;
            case "zap" -> "zolt-zap-" + version;
            default -> throw new ReleaseChannelManifestException(
                    "Release channel manifest has unsupported channel `" + channel + "`.");
        };
    }

    private static void requireExactUrl(String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest "
                            + field
                            + " must be the matching immutable zoltsh/releases asset `"
                            + expected
                            + "`.");
        }
    }

    private static void validateSafeSegment(String field, String value) {
        if (value.isBlank()
                || !value.equals(value.strip())
                || value.contains("/")
                || value.contains("\\")
                || value.contains("..")
                || value.contains(":")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest " + field + " must be one safe path segment.");
        }
    }
}
