package sh.zolt.toolchain.catalog.metadata;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.Architecture;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.platform.OperatingSystem;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraalVmCommunityMetadataResolver implements JavaToolchainMetadataResolver {
    private static final String PROVIDER = "GraalVM Community GitHub releases";
    private static final String RELEASES_URI =
            "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases?per_page=100&page=";
    private static final Map<String, String> HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2026-03-10",
            "User-Agent", "zolt-toolchain");
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");
    private static final int MAX_RELEASE_PAGES = 10;

    private final ToolchainMetadataTransport transport;

    public GraalVmCommunityMetadataResolver(ToolchainMetadataTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<JavaToolchainRelease> resolve(
            JavaToolchainRequest request,
            List<HostPlatform> platforms) {
        Map<String, Object> release = release(request.version()).orElseGet(() -> Map.of());
        if (release.isEmpty()) {
            return List.of();
        }
        String tag = MetadataJson.requiredString(release, "tag_name", PROVIDER);
        String resolvedVersion = tag.substring("jdk-".length());
        List<Object> assets = MetadataJson.array(release.get("assets"), PROVIDER);
        ArrayList<JavaToolchainRelease> releases = new ArrayList<>();
        for (HostPlatform platform : platforms) {
            findAsset(assets, resolvedVersion, platform)
                    .map(asset -> release(tag, resolvedVersion, platform, asset, assets))
                    .ifPresent(releases::add);
        }
        return List.copyOf(releases);
    }

    private Optional<Map<String, Object>> release(String featureVersion) {
        Pattern tagPattern = Pattern.compile("jdk-" + Pattern.quote(featureVersion) + "(?:\\.[0-9]+)+");
        for (int page = 1; page <= MAX_RELEASE_PAGES; page++) {
            ToolchainMetadataResponse response = transport.get(URI.create(RELEASES_URI + page), HEADERS);
            if (!response.successful()) {
                throw new ActionableException(
                        "GitHub could not list GraalVM Community releases: HTTP "
                                + response.statusCode()
                                + ".",
                        "Retry `zolt toolchain sync` or check GitHub API availability.");
            }
            List<Object> releases = MetadataJson.array(MetadataJson.parse(response.body(), PROVIDER), PROVIDER);
            Optional<Map<String, Object>> match = releases.stream()
                    .map(value -> MetadataJson.object(value, PROVIDER))
                    .filter(release -> !MetadataJson.booleanValue(release, "draft"))
                    .filter(release -> !MetadataJson.booleanValue(release, "prerelease"))
                    .filter(release -> tagPattern.matcher(
                            MetadataJson.optionalString(release, "tag_name").orElse("")).matches())
                    .max(Comparator.comparing(
                            release -> versionParts(MetadataJson.requiredString(release, "tag_name", PROVIDER)),
                            GraalVmCommunityMetadataResolver::compareVersionParts));
            if (match.isPresent()) {
                return match;
            }
            if (releases.size() < 100) {
                break;
            }
        }
        return Optional.empty();
    }

    private JavaToolchainRelease release(
            String tag,
            String resolvedVersion,
            HostPlatform platform,
            Map<String, Object> asset,
            List<Object> assets) {
        String name = MetadataJson.requiredString(asset, "name", PROVIDER);
        String digest = MetadataJson.optionalString(asset, "digest")
                .filter(value -> value.startsWith("sha256:"))
                .map(value -> value.substring("sha256:".length()))
                .orElse("");
        String sidecar = findNamedAsset(assets, name + ".sha256")
                .map(this::sidecarChecksum)
                .orElse("");
        if (!digest.isBlank() && !sidecar.isBlank() && !digest.equalsIgnoreCase(sidecar)) {
            throw new ActionableException(
                    "GraalVM Community checksum metadata disagrees for " + name + ".",
                    "Do not install this artifact; retry after the upstream release metadata is corrected.");
        }
        String sha256 = !digest.isBlank() ? digest : sidecar;
        if (sha256.isBlank()) {
            throw new ActionableException(
                    "GraalVM Community did not publish a SHA-256 checksum for " + name + ".",
                    "Choose another release or retry after the upstream release publishes checksum metadata.");
        }
        return new JavaToolchainRelease(
                platform,
                resolvedVersion,
                URI.create(MetadataJson.requiredString(asset, "browser_download_url", PROVIDER)),
                sha256,
                "github:graalvm/graalvm-ce-builds@" + tag);
    }

    private String sidecarChecksum(Map<String, Object> sidecar) {
        URI uri = URI.create(MetadataJson.requiredString(sidecar, "browser_download_url", PROVIDER));
        ToolchainMetadataResponse response = transport.get(uri, Map.of("User-Agent", "zolt-toolchain"));
        if (!response.successful()) {
            throw new ActionableException(
                    "Could not read GraalVM Community checksum sidecar: HTTP "
                            + response.statusCode()
                            + ".",
                    "Retry `zolt toolchain sync` after GitHub release assets are available.");
        }
        Matcher matcher = SHA256.matcher(response.body());
        if (!matcher.find()) {
            throw new ActionableException(
                    "GraalVM Community checksum sidecar did not contain a SHA-256 value.",
                    "Do not install the artifact; retry after the upstream sidecar is corrected.");
        }
        return matcher.group(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static Optional<Map<String, Object>> findAsset(
            List<Object> assets,
            String resolvedVersion,
            HostPlatform platform) {
        String extension = platform.os() == OperatingSystem.WINDOWS ? "zip" : "tar.gz";
        return graalPlatform(platform).flatMap(platformId -> findNamedAsset(
                assets,
                "graalvm-community-jdk-"
                        + resolvedVersion
                        + "_"
                        + platformId
                        + "_bin."
                        + extension));
    }

    private static Optional<Map<String, Object>> findNamedAsset(List<Object> assets, String expected) {
        return assets.stream()
                .map(value -> MetadataJson.object(value, PROVIDER))
                .filter(asset -> expected.equals(MetadataJson.optionalString(asset, "name").orElse("")))
                .findFirst();
    }

    private static Optional<String> graalPlatform(HostPlatform platform) {
        return switch (platform.os()) {
            case LINUX -> Optional.of(platform.arch() == Architecture.X64 ? "linux-x64" : "linux-aarch64");
            case MACOS -> Optional.of(platform.arch() == Architecture.X64 ? "macos-x64" : "macos-aarch64");
            case WINDOWS -> platform.arch() == Architecture.X64
                    ? Optional.of("windows-x64")
                    : Optional.empty();
        };
    }

    private static List<Integer> versionParts(String tag) {
        return java.util.Arrays.stream(tag.substring("jdk-".length()).split("\\."))
                .map(Integer::parseInt)
                .toList();
    }

    private static int compareVersionParts(List<Integer> left, List<Integer> right) {
        int length = Math.max(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            int leftPart = index < left.size() ? left.get(index) : 0;
            int rightPart = index < right.size() ? right.get(index) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
