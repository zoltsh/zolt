package sh.zolt.toolchain.lock;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.HostPlatform;
import java.net.URI;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record LockedJavaToolchain(
        String id,
        JavaToolchainRequest request,
        HostPlatform platform,
        String resolvedVersion,
        JavaDistribution resolvedDistribution,
        String catalog,
        String artifactUri,
        String artifactSha256,
        JavaToolchainLayout layout) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public LockedJavaToolchain {
        id = clean(id, "Java toolchain lock id is required.");
        if (request == null) {
            throw new IllegalArgumentException("Java toolchain request is required.");
        }
        if (platform == null) {
            throw new IllegalArgumentException("Java toolchain platform is required.");
        }
        resolvedVersion = clean(resolvedVersion, "Java toolchain resolved version is required.");
        if (resolvedDistribution == null) {
            throw new IllegalArgumentException("Java toolchain resolved distribution is required.");
        }
        catalog = clean(catalog, "Java toolchain catalog reference is required.");
        artifactUri = secureArtifactUri(artifactUri);
        artifactSha256 = sha256(artifactSha256);
        layout = layout == null ? JavaToolchainLayout.standard(request.requiresNativeImage()) : layout;
    }

    public String featureList() {
        if (request.features().isEmpty()) {
            return "";
        }
        return String.join(", ", request.features().stream()
                .sorted(Comparator.comparing(JavaFeature::id))
                .map(JavaFeature::id)
                .toList());
    }

    public Set<JavaFeature> features() {
        return request.features();
    }

    private static String clean(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String secureArtifactUri(String value) {
        String cleaned = clean(value, "Java toolchain lock artifact URI is required.");
        URI uri;
        try {
            uri = URI.create(cleaned);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Java toolchain lock artifact URI is invalid.", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Java toolchain lock artifact URI must use HTTPS; local and insecure transport require an explicit mirror policy.");
        }
        return uri.toString();
    }

    private static String sha256(String value) {
        String cleaned = clean(value, "Java toolchain lock artifact SHA-256 is required.");
        if (!SHA256.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "Java toolchain lock artifact SHA-256 must contain exactly 64 hexadecimal characters.");
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
