package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class ToolchainDownloadMirrorTest {
    private static final URI TEMURIN = URI.create(
            "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/"
                    + "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz");

    @Test
    void rewritesGithubPrefixToMirrorBase() {
        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of("https://nexus.example.com/github");

        assertEquals(
                URI.create("https://nexus.example.com/github/adoptium/temurin21-binaries/releases/download/"
                        + "jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz"),
                mirror.rewrite(TEMURIN));
    }

    @Test
    void trailingSlashesOnMirrorBaseAreNormalized() {
        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of("https://nexus.example.com/github//");

        assertEquals(
                "https://nexus.example.com/github/adoptium/temurin21-binaries/releases/download/"
                        + "jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz",
                mirror.rewrite(TEMURIN).toString());
    }

    @Test
    void leavesNonGithubUrlsUnchanged() {
        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of("https://nexus.example.com/github");
        URI other = URI.create("https://cdn.example.org/jdk.tar.gz");

        assertSame(other, mirror.rewrite(other));
    }

    @Test
    void leavesFileUrisUnchanged() {
        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of("https://nexus.example.com/github");
        URI file = URI.create("file:///tmp/jdk.tar.gz");

        assertSame(file, mirror.rewrite(file));
    }

    @Test
    void noneAndBlankAreIdentity() {
        assertSame(TEMURIN, ToolchainDownloadMirror.none().rewrite(TEMURIN));
        assertSame(TEMURIN, ToolchainDownloadMirror.of("   ").rewrite(TEMURIN));
    }

    @Test
    void rejectsImplicitOrAmbiguousMirrorUris() {
        for (String mirror : java.util.List.of(
                "relative/mirror",
                "ftp://mirror.example.test/jdks",
                "https://user:secret@mirror.example.test/jdks",
                "https://mirror.example.test/jdks?channel=test",
                "file:///")) {
            assertThrows(IllegalArgumentException.class, () -> ToolchainDownloadMirror.of(mirror), mirror);
        }
    }
}
