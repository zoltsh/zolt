package sh.zolt.toolchain.catalog;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.Architecture;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.platform.OperatingSystem;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class BundledJavaToolchainCatalog implements JavaToolchainCatalog {
    private static final String GRAALVM_JDK_21 = "21.0.2";
    private static final String TEMURIN_JDK_21 = "21.0.11+10";
    private static final List<HostPlatform> SUPPORTED_PLATFORMS = List.of(
            new HostPlatform(OperatingSystem.LINUX, Architecture.X64),
            new HostPlatform(OperatingSystem.LINUX, Architecture.AARCH64),
            new HostPlatform(OperatingSystem.MACOS, Architecture.X64),
            new HostPlatform(OperatingSystem.MACOS, Architecture.AARCH64));
    // windows-x64 is lockable on request (so a Windows host can `toolchain sync` a checksum-verified
    // JDK) but is intentionally NOT part of the default multi-platform set enumerated by available();
    // promoting it into the committed multi-platform lockfile is a separate change.
    private static final List<HostPlatform> LOCKABLE_PLATFORMS = Stream.concat(
                    SUPPORTED_PLATFORMS.stream(),
                    Stream.of(new HostPlatform(OperatingSystem.WINDOWS, Architecture.X64)))
            .toList();

    @Override
    public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
        if (!"21".equals(request.version()) || request.distribution().isEmpty()) {
            return Optional.empty();
        }
        if (!LOCKABLE_PLATFORMS.contains(platform)) {
            return Optional.empty();
        }
        JavaDistribution distribution = request.distribution().orElseThrow();
        if (request.requiresNativeImage() && distribution != JavaDistribution.GRAALVM_COMMUNITY) {
            return Optional.empty();
        }
        if (distribution != JavaDistribution.GRAALVM_COMMUNITY && distribution != JavaDistribution.TEMURIN) {
            return Optional.empty();
        }
        PinnedArtifact artifact = pinnedArtifact(distribution, platform).orElseThrow();
        String id = id(distribution, request);
        return Optional.of(new LockedJavaToolchain(
                id,
                request,
                platform,
                artifact.resolvedVersion(),
                distribution,
                "builtin:" + id,
                artifact.uri(),
                artifact.sha256(),
                layout(distribution, request, platform)));
    }

    @Override
    public List<LockedJavaToolchain> locks(JavaToolchainRequest request, HostPlatform platform) {
        return Stream.concat(SUPPORTED_PLATFORMS.stream(), Stream.of(platform))
                .distinct()
                .map(target -> lock(request, target))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<LockedJavaToolchain> available() {
        return List.of(
                        new JavaToolchainRequest(
                                "21",
                                JavaDistribution.TEMURIN,
                                Set.of(),
                                ToolchainPolicy.PREFER_MANAGED),
                        new JavaToolchainRequest(
                                "21",
                                JavaDistribution.GRAALVM_COMMUNITY,
                                Set.of(JavaFeature.NATIVE_IMAGE),
                                ToolchainPolicy.PREFER_MANAGED))
                .stream()
                .flatMap(request -> locks(request, SUPPORTED_PLATFORMS.getFirst()).stream())
                .toList();
    }

    @Override
    public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
        Optional<PinnedArtifact> pinned = pinnedArtifact(locked.resolvedDistribution(), locked.platform());
        String uri = locked.artifactUri();
        Optional<String> sha256 = locked.artifactSha256().isBlank()
                ? Optional.empty()
                : Optional.of(locked.artifactSha256());
        if (uri.isBlank()) {
            uri = pinned.map(PinnedArtifact::uri).orElse("");
            sha256 = pinned.map(PinnedArtifact::sha256);
        }
        if (uri.isBlank()) {
            return Optional.empty();
        }
        return switch (locked.resolvedDistribution()) {
            case TEMURIN -> Optional.of(new JavaToolchainArtifact(
                    URI.create(uri),
                    archiveFormat(locked.platform()),
                    sha256,
                    true));
            case GRAALVM_COMMUNITY -> Optional.of(new JavaToolchainArtifact(
                    URI.create(uri),
                    archiveFormat(locked.platform()),
                    sha256,
                    true));
            default -> Optional.empty();
        };
    }

    private static String id(JavaDistribution distribution, JavaToolchainRequest request) {
        return "java-" + distribution.id() + "-" + request.version()
                + (request.requiresNativeImage() ? "-native-image" : "");
    }

    private static JavaToolchainLayout layout(
            JavaDistribution distribution,
            JavaToolchainRequest request,
            HostPlatform platform) {
        boolean windows = platform.os() == OperatingSystem.WINDOWS;
        String exe = windows ? ".exe" : "";
        String javaHome = platform.os() == OperatingSystem.MACOS ? "Contents/Home" : ".";
        String nativeImage = "";
        if (request.features().contains(JavaFeature.NATIVE_IMAGE)) {
            if (distribution == JavaDistribution.GRAALVM_COMMUNITY) {
                nativeImage = windows ? "lib/svm/bin/native-image.exe" : "lib/svm/bin/native-image";
            } else {
                nativeImage = "bin/native-image" + exe;
            }
        }
        return new JavaToolchainLayout(
                javaHome,
                "bin/java" + exe,
                "bin/javac" + exe,
                "bin/jar" + exe,
                nativeImage);
    }

    private static Optional<PinnedArtifact> pinnedArtifact(JavaDistribution distribution, HostPlatform platform) {
        return switch (distribution) {
            case TEMURIN -> temurinArtifact(platform);
            case GRAALVM_COMMUNITY -> graalVmCommunityArtifact(platform);
            default -> Optional.empty();
        };
    }

    private static Optional<PinnedArtifact> temurinArtifact(HostPlatform platform) {
        String platformPath = switch (platform.os()) {
            case LINUX -> platform.arch() == Architecture.X64
                    ? "OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz"
                    : "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.11_10.tar.gz";
            case MACOS -> platform.arch() == Architecture.X64
                    ? "OpenJDK21U-jdk_x64_mac_hotspot_21.0.11_10.tar.gz"
                    : "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.11_10.tar.gz";
            case WINDOWS -> "OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip";
        };
        if (platformPath.isBlank()) {
            return Optional.empty();
        }
        String sha256 = switch (platform.id()) {
            case "linux-x64" -> "4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de";
            case "linux-aarch64" -> "8d498ec88e1c1989fab95c6784240ab92d011e29c54d20a3f9c324b13476f9ad";
            case "macos-x64" -> "34180eb03e6d207c388cce3da668f6cc7cd7508c185c24782fadac2c9c0e66f9";
            case "macos-aarch64" -> "6ebcf221c9b41507b14c098e93c6ead6440b8d9bd154f8ec666c4c73abbdb201";
            case "windows-x64" -> "d3625e7cadf23787ea540229544b6e2ab494b3b54da1801879e583e1dfee0a64";
            default -> "";
        };
        if (sha256.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PinnedArtifact(
                TEMURIN_JDK_21,
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/"
                        + platformPath,
                sha256));
    }

    private static Optional<PinnedArtifact> graalVmCommunityArtifact(HostPlatform platform) {
        if (!LOCKABLE_PLATFORMS.contains(platform)) {
            return Optional.empty();
        }
        String sha256 = switch (platform.id()) {
            case "linux-x64" -> "b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48";
            case "linux-aarch64" -> "a34be691ce68f0acf4655c7c6c63a9a49ed276a11859d7224fd94fc2f657cd7a";
            case "macos-x64" -> "7a8aa93fa45d1721908477abf4732a32637d420ffcb66ada9fb6456440b0d9e1";
            case "macos-aarch64" -> "515e3a93acc7e1938daba83eda4272e5495fd302d7cdd99ec7ebf408ed505ab7";
            case "windows-x64" -> "e17b7bead097bf372a5c75df17815b0a2f30b777a019d25eff7706b21421f7fa";
            default -> "";
        };
        if (sha256.isBlank()) {
            return Optional.empty();
        }
        String extension = platform.os() == OperatingSystem.WINDOWS ? "zip" : "tar.gz";
        return Optional.of(new PinnedArtifact(
                GRAALVM_JDK_21,
                "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-"
                        + GRAALVM_JDK_21
                        + "/graalvm-community-jdk-"
                        + GRAALVM_JDK_21
                        + "_"
                        + graalPlatform(platform)
                        + "_bin."
                        + extension,
                sha256));
    }

    private static JavaToolchainArchiveFormat archiveFormat(HostPlatform platform) {
        return platform.os() == OperatingSystem.WINDOWS
                ? JavaToolchainArchiveFormat.ZIP
                : JavaToolchainArchiveFormat.TAR_GZ;
    }

    private static String graalPlatform(HostPlatform platform) {
        return switch (platform.os()) {
            case LINUX -> platform.arch() == Architecture.X64 ? "linux-x64" : "linux-aarch64";
            case MACOS -> platform.arch() == Architecture.X64 ? "macos-x64" : "macos-aarch64";
            case WINDOWS -> "windows-x64";
        };
    }

    private record PinnedArtifact(
            String resolvedVersion,
            String uri,
            String sha256) {
    }
}
