package sh.zolt.release.update;

import sh.zolt.release.channel.ReleaseChannelManifest;
import sh.zolt.release.channel.ReleaseChannelManifestValidator;
import sh.zolt.release.signing.ReleaseSignatureException;
import sh.zolt.release.signing.ReleaseSignatureVerifier;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;

public final class NativeUpdateNoticeService {
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofHours(24);
    private static final int NOTICE_CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int NOTICE_READ_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_CHANNEL_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_CHANNEL_SIGNATURE_BYTES = 8_192;

    private final ReleaseChannelManifestValidator manifestValidator;
    private final ReleaseSignatureVerifier signatureVerifier;

    public NativeUpdateNoticeService() {
        this(new ReleaseChannelManifestValidator(), ReleaseSignatureVerifier.bundled());
    }

    NativeUpdateNoticeService(
            ReleaseChannelManifestValidator manifestValidator,
            ReleaseSignatureVerifier signatureVerifier) {
        this.manifestValidator = manifestValidator;
        this.signatureVerifier = signatureVerifier;
    }

    public Optional<NativeUpdateNotice> check(NativeUpdateNoticeRequest request) {
        if (request.disabled() || request.offline() || request.ci() || !request.interactive()) {
            return Optional.empty();
        }

        NativeInstalledLayout installed;
        try {
            installed = NativeInstalledLayout.detect(request.installRoot(), request.currentExecutable());
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }

        ReleaseChannelUriPolicy.validate(request.channelUri(), true);

        Properties cache = readCache(request.stateDirectory());
        Duration interval = request.checkInterval() == null ? DEFAULT_CHECK_INTERVAL : request.checkInterval();
        if (!checkDue(cache, request.now(), interval)) {
            return cachedNotice(cache, request, installed);
        }

        try {
            byte[] manifestBytes = downloadBytes(
                    request.channelUri(),
                    MAX_CHANNEL_MANIFEST_BYTES,
                    "release channel manifest");
            verifyManifestSignature(request.channelUri(), manifestBytes);
            ReleaseChannelManifest manifest = validateManifest(
                    request.channelUri(),
                    new String(manifestBytes, StandardCharsets.UTF_8));
            ReleaseChannelUriPolicy.requireChannelDocument(
                    request.channelUri(), "channels", manifest.channel());
            writeCache(request, manifest.version(), manifest.channel());
            if (isNewer(installed.version(), manifest.version())) {
                return Optional.of(new NativeUpdateNotice(
                        manifest.channel(),
                        request.target(),
                        installed.version(),
                        manifest.version(),
                        false));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            writeCache(request, cache.getProperty("latestVersion", ""), cache.getProperty("channel", ""));
            return cachedNotice(cache, request, installed);
        }
    }

    private void verifyManifestSignature(URI channelUri, byte[] manifestBytes) throws IOException {
        if (ReleaseChannelUriPolicy.isLocalFile(channelUri)) {
            return;
        }
        URI signatureUri = ReleaseChannelSignatureUris.sidecar(channelUri);
        String sidecarText = new String(
                downloadBytes(
                        signatureUri,
                        MAX_CHANNEL_SIGNATURE_BYTES,
                        "release channel signature"),
                StandardCharsets.UTF_8);
        try {
            signatureVerifier.verify(manifestBytes, sidecarText);
        } catch (ReleaseSignatureException exception) {
            throw new NativeUpdateException(
                    "Release channel signature verification failed: " + exception.getMessage(),
                    exception);
        }
    }

    private static Optional<NativeUpdateNotice> cachedNotice(
            Properties cache,
            NativeUpdateNoticeRequest request,
            NativeInstalledLayout installed) {
        if (!request.channelUri().toString().equals(cache.getProperty("channelUri"))
                || !request.target().id().equals(cache.getProperty("target"))) {
            return Optional.empty();
        }
        String latestVersion = cache.getProperty("latestVersion", "");
        String channel = cache.getProperty("channel", "");
        if (latestVersion.isBlank() || channel.isBlank() || !isNewer(installed.version(), latestVersion)) {
            return Optional.empty();
        }
        return Optional.of(new NativeUpdateNotice(
                channel,
                request.target(),
                installed.version(),
                latestVersion,
                true));
    }

    private ReleaseChannelManifest validateManifest(URI channelUri, String json) {
        if (ReleaseChannelUriPolicy.isLocalFile(channelUri)) {
            return manifestValidator.validateLocalManifest(json);
        }
        return manifestValidator.validate(json);
    }

    private static boolean checkDue(Properties cache, Instant now, Duration interval) {
        String lastCheckedAt = cache.getProperty("lastCheckedAt", "");
        if (lastCheckedAt.isBlank()) {
            return true;
        }
        try {
            Instant last = Instant.parse(lastCheckedAt);
            return Duration.between(last, now).compareTo(interval) >= 0;
        } catch (DateTimeParseException exception) {
            return true;
        }
    }

    private static Properties readCache(Path stateDirectory) {
        Properties properties = new Properties();
        Path cacheFile = cacheFile(stateDirectory);
        if (!Files.isRegularFile(cacheFile)) {
            return properties;
        }
        try (var input = Files.newInputStream(cacheFile)) {
            properties.load(input);
        } catch (IOException exception) {
            return new Properties();
        }
        return properties;
    }

    private static void writeCache(NativeUpdateNoticeRequest request, String latestVersion, String channel) {
        Properties properties = new Properties();
        properties.setProperty("lastCheckedAt", request.now().toString());
        properties.setProperty("channelUri", request.channelUri().toString());
        properties.setProperty("target", request.target().id());
        if (latestVersion != null && !latestVersion.isBlank()) {
            properties.setProperty("latestVersion", latestVersion);
        }
        if (channel != null && !channel.isBlank()) {
            properties.setProperty("channel", channel);
        }
        try {
            Files.createDirectories(request.stateDirectory());
            try (var output = Files.newOutputStream(cacheFile(request.stateDirectory()))) {
                properties.store(output, "Zolt native update check cache");
            }
        } catch (IOException exception) {
            // Update notices must never fail the user's original command.
        }
    }

    private static Path cacheFile(Path stateDirectory) {
        return stateDirectory.resolve("update-check.properties");
    }

    private static byte[] downloadBytes(URI uri, int maxBytes, String description) throws IOException {
        if ("file".equals(uri.getScheme())) {
            try (var input = Files.newInputStream(Path.of(uri))) {
                return readBytes(input, maxBytes, description);
            }
        }
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(NOTICE_CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(NOTICE_READ_TIMEOUT_MILLIS);
        try (var input = connection.getInputStream()) {
            return readBytes(input, maxBytes, description);
        }
    }

    private static byte[] readBytes(java.io.InputStream input, int maxBytes, String description) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new NativeUpdateException("Downloaded " + description + " is too large.");
        }
        return bytes;
    }

    private static boolean isNewer(String currentVersion, String availableVersion) {
        return compareVersions(availableVersion, currentVersion) > 0;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[^A-Za-z0-9]+");
        String[] rightParts = right.split("[^A-Za-z0-9]+");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            String leftPart = part(leftParts, index);
            String rightPart = part(rightParts, index);
            int comparison = comparePart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : "0";
    }

    private static int comparePart(String left, String right) {
        if (left.matches("\\d+") && right.matches("\\d+")) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        return left.compareToIgnoreCase(right);
    }
}
