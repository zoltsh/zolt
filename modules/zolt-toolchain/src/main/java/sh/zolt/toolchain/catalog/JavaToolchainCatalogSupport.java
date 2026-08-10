package sh.zolt.toolchain.catalog;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.Architecture;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.platform.OperatingSystem;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class JavaToolchainCatalogSupport {
    private static final List<HostPlatform> DEFAULT_PLATFORMS = List.of(
            new HostPlatform(OperatingSystem.LINUX, Architecture.X64),
            new HostPlatform(OperatingSystem.LINUX, Architecture.AARCH64),
            new HostPlatform(OperatingSystem.MACOS, Architecture.X64),
            new HostPlatform(OperatingSystem.MACOS, Architecture.AARCH64));

    private JavaToolchainCatalogSupport() {
    }

    static List<HostPlatform> lockPlatforms(HostPlatform host) {
        return Stream.concat(DEFAULT_PLATFORMS.stream(), Stream.of(host))
                .distinct()
                .toList();
    }

    static String id(JavaDistribution distribution, JavaToolchainRequest request) {
        return "java-" + distribution.id() + "-" + request.version()
                + (request.requiresNativeImage() ? "-native-image" : "");
    }

    static JavaToolchainLayout layout(
            JavaDistribution distribution,
            JavaToolchainRequest request,
            HostPlatform platform) {
        boolean windows = platform.os() == OperatingSystem.WINDOWS;
        String executableSuffix = windows ? ".exe" : "";
        String javaHome = platform.os() == OperatingSystem.MACOS ? "Contents/Home" : ".";
        String nativeImage = "";
        if (request.features().contains(JavaFeature.NATIVE_IMAGE)) {
            nativeImage = distribution == JavaDistribution.GRAALVM_COMMUNITY
                    ? windows ? "lib/svm/bin/native-image.exe" : "lib/svm/bin/native-image"
                    : "bin/native-image" + executableSuffix;
        }
        return new JavaToolchainLayout(
                javaHome,
                "bin/java" + executableSuffix,
                "bin/javac" + executableSuffix,
                "bin/jar" + executableSuffix,
                nativeImage);
    }

    static Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
        if (locked.artifactUri().isBlank()) {
            return Optional.empty();
        }
        Optional<String> sha256 = locked.artifactSha256().isBlank()
                ? Optional.empty()
                : Optional.of(locked.artifactSha256());
        return Optional.of(new JavaToolchainArtifact(
                URI.create(locked.artifactUri()),
                archiveFormat(locked.platform()),
                sha256,
                true));
    }

    static JavaToolchainArchiveFormat archiveFormat(HostPlatform platform) {
        return platform.os() == OperatingSystem.WINDOWS
                ? JavaToolchainArchiveFormat.ZIP
                : JavaToolchainArchiveFormat.TAR_GZ;
    }
}
