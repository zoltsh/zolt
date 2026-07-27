package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintFileHasherTest {
    private final BuildFingerprintFileHasher hasher = new BuildFingerprintFileHasher();

    @TempDir
    private Path tempDir;

    @Test
    void directoryHashIgnoresLocalBuildFingerprintFiles() throws IOException {
        Path output = tempDir.resolve("target/classes");
        Files.createDirectories(output.resolve("com/example"));
        Files.writeString(output.resolve("com/example/App.class"), "app");

        String beforeMetadata = hasher.fileHash(output, null, null);
        Files.writeString(output.resolve(".zolt-build-main.fingerprint"), "fingerprint");
        Files.writeString(output.resolve(".zolt-build-main.fingerprint.state"), "state");
        String afterMetadata = hasher.fileHash(output, null, null);
        Files.writeString(output.resolve("com/example/Other.class"), "other");
        String afterClass = hasher.fileHash(output, null, null);

        assertEquals(beforeMetadata, afterMetadata);
        assertNotEquals(afterMetadata, afterClass);
    }

    @Test
    void cachedStateAvoidsReadingCurrentFileContent() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "content");
        BuildFingerprintCachedFileHash cached = BuildFingerprintCachedFileHash.read(source, "cached-hash");
        BuildFingerprintState state = new BuildFingerprintState("fingerprint", Map.of(source.toAbsolutePath().normalize(), cached));

        assertEquals("cached-hash", hasher.fileHash(source, state, null));
    }

    @Test
    void staleCachedStateRehashesOnlyTheChangedFile() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "old");
        BuildFingerprintCachedFileHash cached = BuildFingerprintCachedFileHash.read(source, "cached-hash");
        Files.writeString(source, "newer content");
        BuildFingerprintState state = new BuildFingerprintState(
                "fingerprint",
                Map.of(source.toAbsolutePath().normalize(), cached));

        assertEquals(
                hasher.fileHash(source, null, null),
                hasher.fileHash(source, state, null));
    }

    @Test
    void collectedStateCarriesForwardCurrentCachedHashes() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "content");
        BuildFingerprintCachedFileHash cached = BuildFingerprintCachedFileHash.read(source, "cached-hash");
        BuildFingerprintState state = new BuildFingerprintState(
                "fingerprint",
                Map.of(source.toAbsolutePath().normalize(), cached));
        Map<Path, BuildFingerprintCachedFileHash> collected = new HashMap<>();

        assertEquals("cached-hash", hasher.fileHash(source, state, collected));
        assertEquals(cached, collected.get(source.toAbsolutePath().normalize()));
    }

    @Test
    void collectedStateRecordsFreshFileHashes() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "content");
        Map<Path, BuildFingerprintCachedFileHash> collected = new HashMap<>();

        String hash = hasher.fileHash(source, null, collected);

        assertEquals(hash, collected.get(source.toAbsolutePath().normalize()).hash());
    }

    @Test
    void reusesCurrentLockfileVerifiedArtifactHash() throws IOException {
        Path artifact = tempDir.resolve("artifact.jar");
        Files.writeString(artifact, "artifact bytes");
        BuildFingerprintFileHasher verifiedHasher =
                new BuildFingerprintFileHasher(path -> Optional.of("lockfile-verified-hash"));
        Map<Path, BuildFingerprintCachedFileHash> collected = new HashMap<>();

        assertEquals(
                "lockfile-verified-hash",
                verifiedHasher.fileHash(artifact, null, collected));
        assertEquals(
                "lockfile-verified-hash",
                collected.get(artifact.toAbsolutePath().normalize()).hash());
    }

    @Test
    void classpathHashUsesIncrementalAbiStateForZoltOutputDirectories() throws IOException {
        Path output = tempDir.resolve("target/classes").toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.writeString(output.resolve(".zolt-incremental-main.state"), incrementalState(output));

        String hash = hasher.classpathHash(output, null, null);

        assertEquals(
                "abi:" + hasher.hashText("com.example.Alpha|abi-alpha|package-alpha\n"
                        + "com.example.Beta|abi-beta|package-beta\n"),
                hash);
    }

    @Test
    void classpathHashFallsBackToDirectoryHashWhenIncrementalStateDoesNotMatchOutputDirectory() throws IOException {
        Path output = tempDir.resolve("target/classes").toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.writeString(output.resolve(".zolt-incremental-main.state"), incrementalState(tempDir.resolve("other-output")));

        assertEquals(hasher.fileHash(output, null, null), hasher.classpathHash(output, null, null));
    }

    private String incrementalState(Path outputDirectory) {
        Path project = tempDir.toAbsolutePath().normalize();
        return "version=4\n"
                + "scope=main\n"
                + encodedLine("projectDirectory", project.toString())
                + encodedLine("outputDirectory", outputDirectory.toAbsolutePath().normalize().toString())
                + encodedLine("generatedSourcesDirectory", project.resolve("target/generated/sources/annotations").toString())
                + "compilerSettingsHash=compiler-hash\n"
                + "buildFingerprintSha256=fingerprint-hash\n"
                + classRecord("com.example.Beta", "class-beta", "abi-beta", "package-beta")
                + classRecord("com.example.Alpha", "class-alpha", "abi-alpha", "package-alpha");
    }

    private String classRecord(String binaryName, String classHash, String abiHash, String packageAbiHash) {
        Path outputPath = tempDir.resolve("target/classes")
                .resolve(binaryName.replace('.', '/') + ".class")
                .toAbsolutePath()
                .normalize();
        return encodedRecord(
                "class",
                binaryName,
                outputPath.toString(),
                classHash,
                abiHash,
                packageAbiHash,
                "33",
                "java.lang.Object");
    }

    private static String encodedLine(String name, String value) {
        return name + "=" + encode(value) + "\n";
    }

    private static String encodedRecord(String name, String... values) {
        StringBuilder line = new StringBuilder(name);
        for (String value : values) {
            line.append('\t').append(encode(value));
        }
        return line.append('\n').toString();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
