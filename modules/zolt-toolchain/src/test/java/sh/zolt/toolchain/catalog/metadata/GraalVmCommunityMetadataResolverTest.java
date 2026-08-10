package sh.zolt.toolchain.catalog.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.platform.HostPlatform;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GraalVmCommunityMetadataResolverTest {
    private static final String SHA256 = "b".repeat(64);
    private static final URI RELEASES = URI.create(
            "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases?per_page=100&page=1");

    @Test
    void selectsNewestStablePatchAndOnlyPublishedPlatforms() {
        FakeTransport transport = new FakeTransport(releaseFixture(SHA256));
        transport.respond(
                "https://example.test/graalvm-linux.tar.gz.sha256",
                new ToolchainMetadataResponse(200, SHA256 + "  graalvm-linux.tar.gz\n"));
        transport.respond(
                "https://example.test/graalvm-macos-aarch64.tar.gz.sha256",
                new ToolchainMetadataResponse(200, SHA256 + "  graalvm-macos-aarch64.tar.gz\n"));

        List<JavaToolchainRelease> releases = new GraalVmCommunityMetadataResolver(transport)
                .resolve(graal("25"), List.of(
                        HostPlatform.parse("linux-x64"),
                        HostPlatform.parse("macos-x64"),
                        HostPlatform.parse("macos-aarch64")));

        assertEquals(2, releases.size());
        assertTrue(releases.stream().allMatch(release -> release.resolvedVersion().equals("25.0.2")));
        assertTrue(releases.stream().anyMatch(release -> release.platform().equals(HostPlatform.parse("linux-x64"))));
        assertTrue(releases.stream().anyMatch(release -> release.platform().equals(HostPlatform.parse("macos-aarch64"))));
        assertTrue(releases.stream().noneMatch(release -> release.platform().equals(HostPlatform.parse("macos-x64"))));
        assertTrue(releases.stream().allMatch(release -> release.sha256().equals(SHA256)));
    }

    @Test
    void rejectsDisagreeingDigestAndSidecar() {
        FakeTransport transport = new FakeTransport(releaseFixture(SHA256));
        transport.respond(
                "https://example.test/graalvm-linux.tar.gz.sha256",
                new ToolchainMetadataResponse(200, "c".repeat(64)));

        ActionableException exception = assertThrows(ActionableException.class, () ->
                new GraalVmCommunityMetadataResolver(transport).resolve(
                        graal("25"),
                        List.of(HostPlatform.parse("linux-x64"))));

        assertTrue(exception.getMessage().contains("checksum metadata disagrees"));
    }

    @Test
    void doesNotRelabelWindowsX64AssetForWindowsArm64() {
        FakeTransport transport = new FakeTransport(releaseFixture(SHA256));

        List<JavaToolchainRelease> releases = new GraalVmCommunityMetadataResolver(transport)
                .resolve(graal("25"), List.of(
                        HostPlatform.parse("windows-x64"),
                        HostPlatform.parse("windows-aarch64")));

        assertEquals(1, releases.size());
        assertEquals(HostPlatform.parse("windows-x64"), releases.getFirst().platform());
        assertTrue(releases.getFirst().artifactUri().toString().endsWith("graalvm-windows.zip"));
    }

    private static JavaToolchainRequest graal(String version) {
        return new JavaToolchainRequest(
                version,
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.REQUIRE_MANAGED);
    }

    private static String releaseFixture(String sha256) {
        return """
                [
                  {
                    "tag_name": "jdk-25.0.3",
                    "draft": false,
                    "prerelease": true,
                    "assets": []
                  },
                  {
                    "tag_name": "jdk-25.0.1",
                    "draft": false,
                    "prerelease": false,
                    "assets": []
                  },
                  {
                    "tag_name": "jdk-25.0.2",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      {
                        "name": "graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz",
                        "browser_download_url": "https://example.test/graalvm-linux.tar.gz",
                        "digest": "sha256:%s"
                      },
                      {
                        "name": "graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz.sha256",
                        "browser_download_url": "https://example.test/graalvm-linux.tar.gz.sha256"
                      },
                      {
                        "name": "graalvm-community-jdk-25.0.2_macos-aarch64_bin.tar.gz",
                        "browser_download_url": "https://example.test/graalvm-macos-aarch64.tar.gz",
                        "digest": "sha256:%s"
                      },
                      {
                        "name": "graalvm-community-jdk-25.0.2_macos-aarch64_bin.tar.gz.sha256",
                        "browser_download_url": "https://example.test/graalvm-macos-aarch64.tar.gz.sha256"
                      },
                      {
                        "name": "graalvm-community-jdk-25.0.2_windows-x64_bin.zip",
                        "browser_download_url": "https://example.test/graalvm-windows.zip",
                        "digest": "sha256:%s"
                      }
                    ]
                  }
                ]
                """.formatted(sha256, sha256, sha256);
    }

    private static final class FakeTransport implements ToolchainMetadataTransport {
        private final Map<URI, ToolchainMetadataResponse> responses = new HashMap<>();

        private FakeTransport(String releases) {
            responses.put(RELEASES, new ToolchainMetadataResponse(200, releases));
        }

        private void respond(String uri, ToolchainMetadataResponse response) {
            responses.put(URI.create(uri), response);
        }

        @Override
        public ToolchainMetadataResponse get(URI uri, Map<String, String> headers) {
            return responses.getOrDefault(uri, new ToolchainMetadataResponse(404, ""));
        }
    }
}
