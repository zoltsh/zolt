package sh.zolt.toolchain.catalog.metadata;

import sh.zolt.toolchain.platform.HostPlatform;
import java.net.URI;

public record JavaToolchainRelease(
        HostPlatform platform,
        String resolvedVersion,
        URI artifactUri,
        String sha256,
        String catalog) {
    public JavaToolchainRelease {
        if (platform == null || artifactUri == null) {
            throw new IllegalArgumentException("Java toolchain release platform and URI are required.");
        }
        resolvedVersion = required(resolvedVersion, "resolved version");
        sha256 = required(sha256, "SHA-256").toLowerCase(java.util.Locale.ROOT);
        catalog = required(catalog, "catalog provenance");
        if (!"https".equalsIgnoreCase(artifactUri.getScheme())) {
            throw new IllegalArgumentException("Java toolchain release URI must use HTTPS.");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Java toolchain release SHA-256 must contain 64 hexadecimal characters.");
        }
    }

    private static String required(String value, String subject) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Java toolchain release " + subject + " is required.");
        }
        return value.strip();
    }
}
