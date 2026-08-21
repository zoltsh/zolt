package sh.zolt.build.classpath;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LockfileClasspathPackageConverterTest {
    private final ZoltLockfileReader reader = new ZoltLockfileReader();

    @TempDir
    private Path tempDir;

    @Test
    void ignoresNonJarArtifacts() {
        ZoltLockfile lockfile = migrate(tempDir.resolve("cache"), """
                version = 7

                [[package]]
                id = "io.quarkus.platform:quarkus-bom-quarkus-platform-properties"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                pom = "io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.pom"
                pomSha256 = "pom-checksum"
                artifact = "io/quarkus/platform/quarkus-bom-quarkus-platform-properties/3.33.0/quarkus-bom-quarkus-platform-properties-3.33.0.properties"
                artifactType = "properties"
                artifactSha256 = "properties-checksum"
                dependencies = []
                """);

        assertEquals(List.of(), LockfileClasspathPackageConverter.classpathPackages(lockfile));
    }

    @Test
    void directConverterRefusesLegacyArtifactPaths() {
        String digest = "a".repeat(64);
        ZoltLockfile legacy = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:demo"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "com/example/demo/1.0.0/demo.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(digest));

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> LockfileClasspathPackageConverter.classpathPackages(legacy));

        assertTrue(exception.getMessage().contains("must start with `blobs/v2/sha256/`"));
    }

    @Test
    void directConverterRefusesMalformedContentAddressedPaths() {
        String digest = "a".repeat(64);
        ZoltLockfile malformed = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:demo"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "com/example/demo/1.0.0/demo.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(digest));

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> LockfileClasspathPackageConverter.classpathPackages(malformed));

        assertTrue(exception.getMessage().contains("must start with `blobs/v2/sha256/`"));
    }

    @Test
    void reconstructsClasspathInputsFromLockfileData() throws IOException {
        List<ResolvedClasspathPackage> packages = LockfileClasspathPackageConverter.classpathPackages(
                migrate(tempDir.resolve("cache"), golden()));

        assertEquals(3, packages.size());
        ResolvedClasspathPackage guava = packages.stream()
                .filter(candidate -> candidate.resolvedPackage().packageId().equals(new PackageId("com.google.guava", "guava")))
                .findFirst()
                .orElseThrow();
        assertEquals(DependencyScope.COMPILE, guava.scope());
        assertEquals("guava-33.4.0-jre.jar", guava.resolvedPackage().jarPath().getFileName().toString());
        assertEquals("guava-33.4.0-jre.pom", guava.resolvedPackage().pomPath().getFileName().toString());
    }

    @Test
    void reconstructsClasspathInputsUnderCacheRoot() {
        Path cacheRoot = tempDir.resolve("cache");
        try {
            write(cacheRoot.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar"), "jar");
            write(cacheRoot.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom"), "pom");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        List<ResolvedClasspathPackage> packages = LockfileClasspathPackageConverter.classpathPackages(
                migrate(cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.google.guava:guava"
                version = "33.4.0-jre"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.google.guava:guava"
                version = "33.4.0-jre"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar"
                pom = "com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom"
                dependencies = []
                """), cacheRoot);

        ResolvedClasspathPackage guava = packages.stream()
                .filter(candidate -> candidate.resolvedPackage().packageId().equals(new PackageId("com.google.guava", "guava")))
                .findFirst()
                .orElseThrow();

        assertTrue(guava.resolvedPackage().jarPath().startsWith(cacheRoot));
        assertEquals("guava-33.4.0-jre.jar", guava.resolvedPackage().jarPath().getFileName().toString());
    }

    @Test
    void verifiesArtifactIntegrityBeforeReconstructingCachedClasspathInputs() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "actual jar bytes");
        ZoltLockfile lockfile = migrate(cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:demo"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/demo/1.0.0/demo-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(sha256("expected jar bytes")));

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));

        assertTrue(exception.getMessage().contains("Cached jar integrity check failed for com.example:demo:1.0.0"));
        assertTrue(exception.getMessage().contains("Expected " + sha256("expected jar bytes")));
        assertTrue(exception.getMessage().contains("but found " + sha256(jar)));
        assertTrue(exception.getMessage().contains(cacheRoot.toAbsolutePath().normalize().toString()));
        assertTrue(exception.getMessage().contains("demo-1.0.0.jar"));
        assertTrue(exception.getMessage().contains("Remove the cache entry or run `zolt resolve`"));
    }

    @Test
    void reconstructsWorkspaceClasspathInputsUnderWorkspaceRoot() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot.resolve("modules/core"));
        ZoltLockfile lockfile = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.acme:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                dependencies = []
                """);

        List<ResolvedClasspathPackage> packages = LockfileClasspathPackageConverter.classpathPackages(
                lockfile,
                tempDir.resolve("cache"),
                workspaceRoot);

        assertEquals(
                workspaceRoot.resolve("modules/core/target/classes"),
                packages.getFirst().resolvedPackage().jarPath());
    }

    @Test
    void rejectsWorkspaceClasspathMemberThatEscapesWorkspaceRoot() {
        ZoltLockfile lockfile = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.acme:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "../outside"
                workspaceOutput = "target/classes"
                dependencies = []
                """);

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> LockfileClasspathPackageConverter.classpathPackages(
                        lockfile,
                        tempDir.resolve("cache"),
                        tempDir.resolve("workspace")));

        assertTrue(exception.getMessage().contains("workspace"));
        assertTrue(exception.getMessage().contains("../outside"));
    }

    @Test
    void rejectsWorkspaceClasspathOutputThatEscapesMemberRoot() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot.resolve("modules/core"));
        ZoltLockfile lockfile = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.acme:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "../classes"
                dependencies = []
                """);

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> LockfileClasspathPackageConverter.classpathPackages(
                        lockfile,
                        tempDir.resolve("cache"),
                        workspaceRoot));

        assertTrue(exception.getMessage().contains("workspaceOutput"));
        assertTrue(exception.getMessage().contains("../classes"));
    }

    private static String golden() throws IOException {
        return new String(
                LockfileClasspathPackageConverterTest.class
                        .getResourceAsStream("/golden/zolt-lock-writer.golden")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static ZoltLockfile migrate(Path cacheRoot, String content) {
        try {
            return sh.zolt.build.lockfile.ContentAddressedLockTestSupport.migrate(cacheRoot, content);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
