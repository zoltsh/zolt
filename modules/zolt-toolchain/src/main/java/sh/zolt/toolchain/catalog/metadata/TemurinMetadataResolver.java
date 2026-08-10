package sh.zolt.toolchain.catalog.metadata;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.Architecture;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.platform.OperatingSystem;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TemurinMetadataResolver implements JavaToolchainMetadataResolver {
    private static final String PROVIDER = "Adoptium API";
    private static final String CATALOG = "adoptium-api:v3";
    private static final Map<String, String> HEADERS = Map.of(
            "Accept", "application/json",
            "User-Agent", "zolt-toolchain");

    private final ToolchainMetadataTransport transport;

    public TemurinMetadataResolver(ToolchainMetadataTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<JavaToolchainRelease> resolve(
            JavaToolchainRequest request,
            List<HostPlatform> platforms) {
        if (request.requiresNativeImage()) {
            throw new ActionableException(
                    "Temurin does not publish the Native Image feature requested for Java "
                            + request.version()
                            + ".",
                    "Use distribution = `graalvm-community` for features = [`native-image`], or clear the feature for Temurin.");
        }
        ArrayList<JavaToolchainRelease> releases = new ArrayList<>();
        for (HostPlatform platform : platforms) {
            resolve(request.version(), platform).ifPresent(releases::add);
        }
        return List.copyOf(releases);
    }

    private java.util.Optional<JavaToolchainRelease> resolve(
            String featureVersion,
            HostPlatform platform) {
        URI uri = metadataUri(featureVersion, platform);
        ToolchainMetadataResponse response = transport.get(uri, HEADERS);
        if (response.statusCode() == 404) {
            return java.util.Optional.empty();
        }
        if (!response.successful()) {
            throw new ActionableException(
                    "Adoptium could not resolve Temurin Java "
                            + featureVersion
                            + " for "
                            + platform.id()
                            + ": HTTP "
                            + response.statusCode()
                            + ".",
                    "Retry `zolt toolchain sync` or choose a Java feature/platform published by Temurin.");
        }
        List<Object> assets = MetadataJson.array(MetadataJson.parse(response.body(), PROVIDER), PROVIDER);
        if (assets.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> asset = MetadataJson.object(assets.getFirst(), PROVIDER);
        Map<String, Object> version = MetadataJson.object(asset.get("version"), PROVIDER);
        Map<String, Object> binary = MetadataJson.object(asset.get("binary"), PROVIDER);
        Map<String, Object> packageMetadata = MetadataJson.object(binary.get("package"), PROVIDER);
        String resolvedVersion = MetadataJson.requiredString(version, "semver", PROVIDER);
        if (!resolvedVersion.equals(featureVersion) && !resolvedVersion.startsWith(featureVersion + ".")) {
            throw new ActionableException(
                    "Adoptium returned Temurin "
                            + resolvedVersion
                            + " for requested Java feature "
                            + featureVersion
                            + ".",
                    "Retry after the upstream metadata is corrected; Zolt will not lock a mismatched Java feature.");
        }
        return java.util.Optional.of(new JavaToolchainRelease(
                platform,
                resolvedVersion,
                URI.create(MetadataJson.requiredString(packageMetadata, "link", PROVIDER)),
                MetadataJson.requiredString(packageMetadata, "checksum", PROVIDER),
                CATALOG));
    }

    private static URI metadataUri(String featureVersion, HostPlatform platform) {
        return URI.create("https://api.adoptium.net/v3/assets/latest/"
                + featureVersion
                + "/hotspot?architecture="
                + architecture(platform.arch())
                + "&heap_size=normal&image_type=jdk&os="
                + operatingSystem(platform.os())
                + "&project=jdk&vendor=eclipse");
    }

    private static String operatingSystem(OperatingSystem os) {
        return switch (os) {
            case LINUX -> "linux";
            case MACOS -> "mac";
            case WINDOWS -> "windows";
        };
    }

    private static String architecture(Architecture architecture) {
        return architecture == Architecture.X64 ? "x64" : "aarch64";
    }
}
