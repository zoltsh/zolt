package sh.zolt.toolchain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BundledJavaToolchainCatalogTest {
    private final BundledJavaToolchainCatalog catalog = new BundledJavaToolchainCatalog();

    @Test
    void locksGraalVmJavaTwentyOneNativeImageLane() {
        Optional<LockedJavaToolchain> locked = catalog.lock(
                new JavaToolchainRequest(
                        "21",
                        JavaDistribution.GRAALVM_COMMUNITY,
                        Set.of(JavaFeature.NATIVE_IMAGE),
                        ToolchainPolicy.PREFER_MANAGED),
                HostPlatform.parse("linux-x64"));

        LockedJavaToolchain java = locked.orElseThrow();
        assertEquals("java-graalvm-community-21-native-image", java.id());
        assertEquals("21.0.2", java.resolvedVersion());
        assertEquals("builtin:java-graalvm-community-21-native-image", java.catalog());
        assertTrue(java.artifactUri().endsWith("graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz"));
        assertEquals("b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48", java.artifactSha256());
        assertEquals("lib/svm/bin/native-image", java.layout().nativeImage());
        JavaToolchainArtifact artifact = catalog.artifact(java).orElseThrow();
        assertEquals(JavaToolchainArchiveFormat.TAR_GZ, artifact.format());
        assertEquals(java.artifactUri(), artifact.uri().toString());
        assertEquals(java.artifactSha256(), artifact.sha256().orElseThrow());
    }

    @Test
    void locksMacArchivesUnderContentsHome() {
        LockedJavaToolchain locked = catalog.lock(
                        new JavaToolchainRequest(
                                "21",
                                JavaDistribution.GRAALVM_COMMUNITY,
                                Set.of(JavaFeature.NATIVE_IMAGE),
                                ToolchainPolicy.PREFER_MANAGED),
                        HostPlatform.parse("macos-aarch64"))
                .orElseThrow();

        assertEquals("Contents/Home", locked.layout().javaHome());
        assertEquals("bin/java", locked.layout().java());
        assertEquals("lib/svm/bin/native-image", locked.layout().nativeImage());
    }

    @Test
    void locksStableUnixPlatformMatrix() {
        List<LockedJavaToolchain> locked = catalog.locks(
                new JavaToolchainRequest(
                        "21",
                        JavaDistribution.GRAALVM_COMMUNITY,
                        Set.of(JavaFeature.NATIVE_IMAGE),
                        ToolchainPolicy.PREFER_MANAGED),
                HostPlatform.parse("macos-aarch64"));

        // windows-x64 is lockable on request but intentionally absent from the default matrix.
        assertEquals(List.of("linux-x64", "linux-aarch64", "macos-x64", "macos-aarch64"), locked.stream()
                .map(java -> java.platform().id())
                .toList());
    }

    @Test
    void addsWindowsHostToStablePlatformMatrixOnRequest() {
        List<LockedJavaToolchain> locked = catalog.locks(
                new JavaToolchainRequest(
                        "21",
                        JavaDistribution.GRAALVM_COMMUNITY,
                        Set.of(JavaFeature.NATIVE_IMAGE),
                        ToolchainPolicy.PREFER_MANAGED),
                HostPlatform.parse("windows-x64"));

        assertEquals(
                List.of("linux-x64", "linux-aarch64", "macos-x64", "macos-aarch64", "windows-x64"),
                locked.stream().map(java -> java.platform().id()).toList());
    }

    @Test
    void locksWindowsGraalVmNativeImageToolchainWithExeLayout() {
        LockedJavaToolchain locked = catalog.lock(
                new JavaToolchainRequest(
                        "21",
                        JavaDistribution.GRAALVM_COMMUNITY,
                        Set.of(JavaFeature.NATIVE_IMAGE),
                        ToolchainPolicy.PREFER_MANAGED),
                HostPlatform.parse("windows-x64")).orElseThrow();

        assertEquals("bin/java.exe", locked.layout().java());
        assertEquals("bin/javac.exe", locked.layout().javac());
        assertEquals("bin/jar.exe", locked.layout().jar());
        assertEquals("lib/svm/bin/native-image.cmd", locked.layout().nativeImage());
        assertTrue(locked.artifactUri().endsWith("graalvm-community-jdk-21.0.2_windows-x64_bin.zip"));
        assertEquals(
                "e17b7bead097bf372a5c75df17815b0a2f30b777a019d25eff7706b21421f7fa",
                locked.artifactSha256());

        JavaToolchainArtifact artifact = catalog.artifact(locked).orElseThrow();
        assertEquals(JavaToolchainArchiveFormat.ZIP, artifact.format());
        assertEquals(locked.artifactUri(), artifact.uri().toString());
    }

    @Test
    void rejectsTemurinNativeImageLaneUntilThereIsAnAdapter() {
        Optional<LockedJavaToolchain> locked = catalog.lock(
                new JavaToolchainRequest(
                        "21",
                        JavaDistribution.TEMURIN,
                        Set.of(JavaFeature.NATIVE_IMAGE),
                        ToolchainPolicy.PREFER_MANAGED),
                HostPlatform.parse("linux-x64"));

        assertTrue(locked.isEmpty());
    }

    @Test
    void resolvesTemurinArtifactToPinnedReleaseAsset() {
        LockedJavaToolchain locked = catalog.lock(
                        new JavaToolchainRequest(
                                "21",
                                JavaDistribution.TEMURIN,
                                Set.of(),
                                ToolchainPolicy.PREFER_MANAGED),
                        HostPlatform.parse("macos-aarch64"))
                .orElseThrow();

        JavaToolchainArtifact artifact = catalog.artifact(locked).orElseThrow();

        assertEquals("21.0.11+10", locked.resolvedVersion());
        assertEquals(JavaToolchainArchiveFormat.TAR_GZ, artifact.format());
        assertEquals(
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz",
                artifact.uri().toString());
        assertEquals(
                "6ebcf221c9b41507b14c098e93c6ead6440b8d9bd154f8ec666c4c73abbdb201",
                artifact.sha256().orElseThrow());
    }

    @Test
    void resolvesTemurinWindowsArtifactToPinnedZip() {
        LockedJavaToolchain locked = catalog.lock(
                        new JavaToolchainRequest(
                                "21",
                                JavaDistribution.TEMURIN,
                                Set.of(),
                                ToolchainPolicy.PREFER_MANAGED),
                        HostPlatform.parse("windows-x64"))
                .orElseThrow();

        JavaToolchainArtifact artifact = catalog.artifact(locked).orElseThrow();

        assertEquals("bin/java.exe", locked.layout().java());
        assertEquals(JavaToolchainArchiveFormat.ZIP, artifact.format());
        assertEquals(
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip",
                artifact.uri().toString());
        assertEquals(
                "d3625e7cadf23787ea540229544b6e2ab494b3b54da1801879e583e1dfee0a64",
                artifact.sha256().orElseThrow());
    }

    @Test
    void availableListsTemurinAndGraalVmNativeImagePlatformMatrix() {
        List<LockedJavaToolchain> available = catalog.available();

        assertEquals(8, available.size());
        assertEquals(4, available.stream()
                .filter(locked -> locked.resolvedDistribution() == JavaDistribution.TEMURIN)
                .count());
        assertEquals(4, available.stream()
                .filter(locked -> locked.resolvedDistribution() == JavaDistribution.GRAALVM_COMMUNITY)
                .filter(locked -> locked.request().requiresNativeImage())
                .count());
        assertTrue(available.stream()
                .anyMatch(locked -> locked.platform().id().equals("macos-aarch64")
                        && locked.resolvedVersion().equals("21.0.2")
                        && locked.artifactSha256().equals("515e3a93acc7e1938daba83eda4272e5495fd302d7cdd99ec7ebf408ed505ab7")));
    }

    @Test
    void resolvesLegacyLocksWithoutArtifactFieldsThroughPinnedCatalog() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.TEMURIN,
                Set.of(),
                ToolchainPolicy.PREFER_MANAGED);
        LockedJavaToolchain legacy = new LockedJavaToolchain(
                "java-temurin-21",
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                JavaDistribution.TEMURIN,
                "builtin:java-temurin-21",
                JavaToolchainLayout.standard(false));

        JavaToolchainArtifact artifact = catalog.artifact(legacy).orElseThrow();

        assertEquals(
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz",
                artifact.uri().toString());
        assertEquals(
                "4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de",
                artifact.sha256().orElseThrow());
    }
}
