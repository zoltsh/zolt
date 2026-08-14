package sh.zolt.build.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class StandaloneArtifactVerificationIndexTest {
    @TempDir
    private Path tempDir;

    @Test
    void freshnessAndClasspathProjectionUseOneHashPerPath() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("blobs/jar"), "jar bytes");
        Path pom = write(cacheRoot.resolve("blobs/pom"), "pom bytes");
        ZoltLockfile lockfile = lockfile(cacheRoot, jar, pom);
        VerifiedArtifactIndex index = new VerifiedArtifactIndex();
        AtomicInteger hashes = new AtomicInteger();
        ArtifactIntegrityVerifier freshness = new ArtifactIntegrityVerifier(
                index,
                1,
                (path, lockPackage, kind, expected) -> {
                    hashes.incrementAndGet();
                    try {
                        return sha256(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });

        freshness.verify(lockfile, cacheRoot);
        LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot, index);

        assertEquals(2, hashes.get());
        assertEquals(2, index.metrics().hashes());
        assertEquals(2, index.metrics().cacheHits());
    }

    private static ZoltLockfile lockfile(Path cacheRoot, Path jar, Path pom) throws IOException {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(new LockPackage(
                        new PackageId("com.example", "demo"),
                        "1.0.0",
                        "maven-central",
                        DependencyScope.COMPILE,
                        true,
                        relative(cacheRoot, jar),
                        relative(cacheRoot, pom),
                        Optional.of(sha256(jar)),
                        Optional.of(sha256(pom)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of())),
                List.of());
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Optional<String> relative(Path root, Path path) {
        return Optional.of(root.relativize(path).toString().replace('\\', '/'));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
