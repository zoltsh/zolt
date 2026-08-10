package sh.zolt.toolchain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.catalog.metadata.JavaToolchainMetadataResolver;
import sh.zolt.toolchain.catalog.metadata.JavaToolchainRelease;
import sh.zolt.toolchain.platform.HostPlatform;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ResolvingJavaToolchainCatalogTest {
    private static final String SHA256 = "d".repeat(64);

    @Test
    void locksExactProviderMetadataWithoutInventingMissingPlatforms() {
        HostPlatform linux = HostPlatform.parse("linux-x64");
        JavaToolchainMetadataResolver resolver = (request, platforms) -> List.of(new JavaToolchainRelease(
                linux,
                "25.0.2",
                URI.create("https://example.test/graalvm.tar.gz"),
                SHA256,
                "provider:test"));
        ResolvingJavaToolchainCatalog catalog = new ResolvingJavaToolchainCatalog(
                Map.of(JavaDistribution.GRAALVM_COMMUNITY, resolver),
                new EmptyCatalog());

        List<sh.zolt.toolchain.lock.LockedJavaToolchain> locks = catalog.locks(graal25(), linux);

        assertEquals(1, locks.size());
        assertEquals("25.0.2", locks.getFirst().resolvedVersion());
        assertEquals("https://example.test/graalvm.tar.gz", locks.getFirst().artifactUri());
        assertEquals(SHA256, locks.getFirst().artifactSha256());
    }

    @Test
    void failsWhenUpstreamDoesNotPublishTheHostPlatform() {
        HostPlatform linux = HostPlatform.parse("linux-x64");
        HostPlatform missingHost = HostPlatform.parse("macos-x64");
        JavaToolchainMetadataResolver resolver = (request, platforms) -> List.of(new JavaToolchainRelease(
                linux,
                "25.0.2",
                URI.create("https://example.test/graalvm.tar.gz"),
                SHA256,
                "provider:test"));
        ResolvingJavaToolchainCatalog catalog = new ResolvingJavaToolchainCatalog(
                Map.of(JavaDistribution.GRAALVM_COMMUNITY, resolver),
                new EmptyCatalog());

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> catalog.locks(graal25(), missingHost));

        assertTrue(exception.getMessage().contains("published for macos-x64"));
    }

    private static JavaToolchainRequest graal25() {
        return new JavaToolchainRequest(
                "25",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.REQUIRE_MANAGED);
    }

    private static final class EmptyCatalog implements JavaToolchainCatalog {
        @Override
        public java.util.Optional<sh.zolt.toolchain.lock.LockedJavaToolchain> lock(
                JavaToolchainRequest request,
                HostPlatform platform) {
            return java.util.Optional.empty();
        }
    }
}
