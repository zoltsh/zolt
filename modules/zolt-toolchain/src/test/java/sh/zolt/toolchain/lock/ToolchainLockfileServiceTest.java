package sh.zolt.toolchain.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.platform.HostPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainLockfileServiceTest {
    @TempDir
    private Path tempDir;

    private final ToolchainLockfileService lockfiles = new ToolchainLockfileService();

    @Test
    void appendsJavaToolchainLockWithoutDisturbingPackages() throws IOException {
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, """
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
                source = "maven"
                scope = "compile"
                direct = true
                dependencies = []
                """);
        LockedJavaToolchain locked = locked("linux-x64");

        lockfiles.writeJava(lockfile, locked);

        String content = Files.readString(lockfile);
        assertTrue(content.contains("[[package]]"));
        assertTrue(content.contains("[[toolchain.java]]"));
        assertTrue(content.contains("request.distribution = \"graalvm-community\""));
        assertTrue(content.contains("artifact.uri = \"https://example.com/graalvm.tar.gz\""));
        assertTrue(content.contains("artifact.sha256 = \"" + "a".repeat(64) + "\""));
        assertTrue(content.contains("layout.executables.nativeImage = \"bin/native-image\""));
        List<LockedJavaToolchain> read = lockfiles.readJava(lockfile);
        assertEquals(1, read.size());
        assertEquals("java-graalvm-community-21-native-image", read.getFirst().id());
        assertEquals("https://example.com/graalvm.tar.gz", read.getFirst().artifactUri());
        assertEquals("a".repeat(64), read.getFirst().artifactSha256());
    }

    @Test
    void replacesPreviousJavaToolchainLock() throws IOException {
        Path lockfile = tempDir.resolve("zolt.lock");

        lockfiles.writeJava(lockfile, locked("linux-x64"));
        lockfiles.writeJava(lockfile, locked("macos-aarch64"));

        String content = Files.readString(lockfile);
        assertTrue(content.startsWith("version = 7\n\n"));
        assertEquals(1, content.split("\\[\\[toolchain\\.java]]", -1).length - 1);
        assertEquals("macos-aarch64", lockfiles.readJava(lockfile).getFirst().platform().id());
    }

    @Test
    void writesDeterministicJavaToolchainPlatformMatrix() throws IOException {
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, """
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
                source = "maven"
                scope = "compile"
                direct = true
                dependencies = []

                [[toolchain.java]]
                id = "stale"
                request.version = "17"
                request.distribution = "temurin"
                request.features = []
                request.policy = "prefer-managed"
                platform.os = "linux"
                platform.arch = "x64"
                resolved.version = "17"
                resolved.distribution = "temurin"
                artifact.catalog = "stale"
                layout.javaHome = "."
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                """);

        lockfiles.writeJava(lockfile, List.of(
                locked("macos-aarch64"),
                locked("linux-aarch64"),
                locked("macos-x64"),
                locked("linux-x64")));

        String content = Files.readString(lockfile);
        assertTrue(content.contains("[[package]]"));
        assertEquals(4, content.split("\\[\\[toolchain\\.java]]", -1).length - 1);
        assertEquals(4, lockfiles.readJava(lockfile).size());
        assertBefore(content, "platform.arch = \"x64\"", "platform.arch = \"aarch64\"");
        assertBefore(content, "platform.os = \"linux\"", "platform.os = \"macos\"");
        assertTrue(!content.contains("id = \"stale\""));
    }

    @Test
    void rejectsChecksumlessLegacyLockWithRefreshRemediation() {
        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> lockfiles.readJava(lockContent(
                        "https://example.test/jdk.tar.gz",
                        "")));

        assertTrue(exception.getMessage().contains("artifact SHA-256 is required"));
        assertTrue(exception.getMessage().contains("toolchain sync --refresh"));
    }

    @Test
    void rejectsMalformedChecksumAtLockBoundary() {
        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> lockfiles.readJava(lockContent(
                        "https://example.test/jdk.tar.gz",
                        "artifact.sha256 = \"abc123\"")));

        assertTrue(exception.getMessage().contains("exactly 64 hexadecimal characters"));
        assertTrue(exception.getMessage().contains("toolchain sync --refresh"));
    }

    @Test
    void rejectsHttpAndFileArtifactsAtLockBoundary() {
        for (String uri : List.of(
                "http://example.test/jdk.tar.gz",
                "file:///tmp/jdk.tar.gz")) {
            ActionableException exception = assertThrows(
                    ActionableException.class,
                    () -> lockfiles.readJava(lockContent(
                            uri,
                            "artifact.sha256 = \"" + "a".repeat(64) + "\"")),
                    uri);

            assertTrue(exception.getMessage().contains("must use HTTPS"), uri);
            assertTrue(exception.getMessage().contains("toolchain sync --refresh"), uri);
        }
    }

    @Test
    void normalizesValidUppercaseChecksum() {
        String uppercase = "ABCDEF01".repeat(8);

        LockedJavaToolchain locked = lockfiles.readJava(lockContent(
                "https://example.test/jdk.tar.gz",
                "artifact.sha256 = \"" + uppercase + "\""))
                .getFirst();

        assertEquals(uppercase.toLowerCase(java.util.Locale.ROOT), locked.artifactSha256());
    }

    @Test
    void rejectsPreV7Reads() {
        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> lockfiles.readJava("version = 6\n\n"));

        assertTrue(exception.getMessage().contains("unsupported version 6; current version is 7"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void rejectsPreV7WritesWithoutMutatingTheLockfile() throws IOException {
        Path lockfile = tempDir.resolve("legacy.lock");
        String legacy = "version = 6\n\n";
        Files.writeString(lockfile, legacy);

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> lockfiles.writeJava(lockfile, locked("linux-x64")));

        assertTrue(exception.getMessage().contains("unsupported version 6; current version is 7"));
        assertEquals(legacy, Files.readString(lockfile));
    }

    @Test
    void rejectsInvalidV7DependencyEvidenceOnReadAndWriteWithoutMutation() throws IOException {
        String invalid = """
                version = 7

                [[package]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """;
        Path lockfile = tempDir.resolve("invalid-v7.lock");
        Files.writeString(lockfile, invalid);

        LockfileReadException readFailure = assertThrows(
                LockfileReadException.class,
                () -> lockfiles.readJava(invalid));
        LockfileReadException writeFailure = assertThrows(
                LockfileReadException.class,
                () -> lockfiles.writeJava(lockfile, locked("linux-x64")));

        assertTrue(readFailure.getMessage().contains("has no exact dependencyRoot"));
        assertTrue(writeFailure.getMessage().contains("has no exact dependencyRoot"));
        assertEquals(invalid, Files.readString(lockfile));
    }

    private static void assertBefore(String content, String first, String second) {
        assertTrue(content.indexOf(first) >= 0, first);
        assertTrue(content.indexOf(second) >= 0, second);
        assertTrue(content.indexOf(first) < content.indexOf(second), first + " should appear before " + second);
    }

    private static String lockContent(String uri, String sha256Assignment) {
        return """
                version = 7

                [[toolchain.java]]
                id = "java-temurin-21"
                request.version = "21"
                request.distribution = "temurin"
                request.features = []
                request.policy = "require-managed"
                platform.os = "linux"
                platform.arch = "x64"
                resolved.version = "21.0.11+10"
                resolved.distribution = "temurin"
                artifact.catalog = "test"
                artifact.uri = "%s"
                %s
                layout.javaHome = "."
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                """.formatted(uri, sha256Assignment);
    }

    private static LockedJavaToolchain locked(String target) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED);
        return new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse(target),
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                "builtin:java-graalvm-community-21-native-image",
                "https://example.com/graalvm.tar.gz",
                "a".repeat(64),
                JavaToolchainLayout.standard(true));
    }
}
