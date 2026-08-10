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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TemurinMetadataResolverTest {
    private static final String SHA256 = "a".repeat(64);

    @Test
    void resolvesExactVersionUrlAndChecksumFromAdoptium() {
        RecordingTransport transport = new RecordingTransport(new ToolchainMetadataResponse(200, """
                [{
                  "binary": {"package": {
                    "checksum": "%s",
                    "link": "https://example.test/temurin-25.0.4.tar.gz"
                  }},
                  "version": {"semver": "25.0.4+7"}
                }]
                """.formatted(SHA256)));
        HostPlatform linux = HostPlatform.parse("linux-x64");

        List<JavaToolchainRelease> releases = new TemurinMetadataResolver(transport)
                .resolve(temurin("25", Set.of()), List.of(linux));

        assertEquals(1, releases.size());
        assertEquals("25.0.4+7", releases.getFirst().resolvedVersion());
        assertEquals(URI.create("https://example.test/temurin-25.0.4.tar.gz"), releases.getFirst().artifactUri());
        assertEquals(SHA256, releases.getFirst().sha256());
        assertTrue(transport.requests.getFirst().toString().contains("/assets/latest/25/hotspot"));
        assertTrue(transport.requests.getFirst().toString().contains("architecture=x64"));
        assertTrue(transport.requests.getFirst().toString().contains("os=linux"));
    }

    @Test
    void rejectsNativeImageWithoutQueryingAdoptium() {
        RecordingTransport transport = new RecordingTransport(new ToolchainMetadataResponse(200, "[]"));

        ActionableException exception = assertThrows(ActionableException.class, () ->
                new TemurinMetadataResolver(transport).resolve(
                        temurin("25", Set.of(JavaFeature.NATIVE_IMAGE)),
                        List.of(HostPlatform.parse("linux-x64"))));

        assertTrue(exception.getMessage().contains("Temurin does not publish"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void rejectsAProviderResponseFromAnotherFeatureLine() {
        RecordingTransport transport = new RecordingTransport(new ToolchainMetadataResponse(200, """
                [{
                  "binary": {"package": {
                    "checksum": "%s",
                    "link": "https://example.test/temurin.tar.gz"
                  }},
                  "version": {"semver": "24.0.2+12"}
                }]
                """.formatted(SHA256)));

        ActionableException exception = assertThrows(ActionableException.class, () ->
                new TemurinMetadataResolver(transport).resolve(
                        temurin("25", Set.of()),
                        List.of(HostPlatform.parse("linux-x64"))));

        assertTrue(exception.getMessage().contains("requested Java feature 25"));
    }

    private static JavaToolchainRequest temurin(String version, Set<JavaFeature> features) {
        return new JavaToolchainRequest(
                version,
                JavaDistribution.TEMURIN,
                features,
                ToolchainPolicy.REQUIRE_MANAGED);
    }

    private static final class RecordingTransport implements ToolchainMetadataTransport {
        private final ToolchainMetadataResponse response;
        private final List<URI> requests = new ArrayList<>();

        private RecordingTransport(ToolchainMetadataResponse response) {
            this.response = response;
        }

        @Override
        public ToolchainMetadataResponse get(URI uri, Map<String, String> headers) {
            requests.add(uri);
            return response;
        }
    }
}
