package sh.zolt.toolchain.install;

import sh.zolt.net.NetworkTransport;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ToolchainInstallFixtures {
    private ToolchainInstallFixtures() {
    }

    static Path archive(Path directory, String home, boolean complete) throws IOException {
        Files.createDirectories(directory);
        Path archive = directory.resolve("jdk.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String tool : complete ? List.of("java", "javac", "jar") : List.of("java")) {
                zip.putNextEntry(new ZipEntry("jdk/" + (home.equals(".") ? "" : home + "/") + "bin/" + tool));
                zip.write(("#!/bin/sh\nprintf '" + tool + "-ok\\n'\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    static LockedJavaToolchain locked(Path archive, String version, String home) throws IOException {
        return new LockedJavaToolchain("java-temurin-" + version,
                new JavaToolchainRequest(version, JavaDistribution.TEMURIN, Set.of(), ToolchainPolicy.REQUIRE_MANAGED),
                HostPlatform.parse("windows-x64"), version, JavaDistribution.TEMURIN,
                "test", "https://github.com/jdk.zip", sha256(archive),
                new JavaToolchainLayout(home, "bin/java", "bin/javac", "bin/jar", ""));
    }

    static Path project(Path directory, LockedJavaToolchain locked) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "toolchain-test"
                version = "0.1.0"
                group = "com.example"
                java = 21
                [toolchain.java]
                version = %s
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(locked.request().version()));
        new ToolchainLockfileService().writeJava(directory.resolve("zolt.lock"), locked);
        return directory;
    }

    static JavaToolchainInstaller installer(Path archive) {
        return new JavaToolchainInstaller(new JavaToolchainDownloader(NetworkTransport.direct(),
                ToolchainDownloadMirror.of(archive.getParent().toUri().toString())));
    }

    static JavaToolchainArtifact artifact(LockedJavaToolchain locked) {
        return new JavaToolchainArtifact(URI.create(locked.artifactUri()), JavaToolchainArchiveFormat.ZIP,
                Optional.of(locked.artifactSha256()), true);
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
