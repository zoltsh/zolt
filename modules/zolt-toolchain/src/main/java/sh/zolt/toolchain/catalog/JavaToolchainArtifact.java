package sh.zolt.toolchain.catalog;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record JavaToolchainArtifact(
        URI uri,
        JavaToolchainArchiveFormat format,
        Optional<String> sha256,
        boolean stripTopLevelDirectory) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public JavaToolchainArtifact {
        if (uri == null) {
            throw new IllegalArgumentException("Java toolchain artifact URI is required.");
        }
        if (format == null) {
            throw new IllegalArgumentException("Java toolchain artifact archive format is required.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Java toolchain artifact URI must use HTTPS; local and insecure transport require an explicit mirror policy.");
        }
        String digest = sha256 == null ? "" : sha256.map(String::strip).orElse("");
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException(
                    "Java toolchain artifact SHA-256 must contain exactly 64 hexadecimal characters.");
        }
        sha256 = Optional.of(digest.toLowerCase(Locale.ROOT));
    }
}
