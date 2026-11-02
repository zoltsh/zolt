package sh.zolt.build.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IncrementalCompileOutputDigestsTest {
    @TempDir
    private Path output;

    @Test
    void directKotlinModuleBytesParticipateInEveryDigest() throws IOException {
        Path module = write("META-INF/demo.kotlin_module", "before");
        IncrementalCompileOutputDigests before = capture();

        Files.writeString(module, "after");
        IncrementalCompileOutputDigests after = capture();

        assertAllDigestsChanged(before, after);
    }

    @Test
    void directKotlinModulePathParticipatesInEveryDigest() throws IOException {
        Path first = write("META-INF/first.kotlin_module", "same bytes");
        IncrementalCompileOutputDigests before = capture();

        Files.move(first, output.resolve("META-INF/second.kotlin_module"));
        IncrementalCompileOutputDigests after = capture();

        assertAllDigestsChanged(before, after);
    }

    @Test
    void ignoresNonDirectAndNonFileKotlinModulePaths() throws IOException {
        IncrementalCompileOutputDigests before = capture();

        write("outside.kotlin_module", "outside");
        write("META-INF/nested/ignored.kotlin_module", "nested");
        write("META-INF/resource.txt", "resource");
        Files.createDirectory(output.resolve("META-INF/directory.kotlin_module"));

        assertEquals(before, capture());
    }

    @Test
    void ignoresSymlinkedKotlinModuleMetadata() throws IOException {
        Path target = write("actual-metadata", "outside metadata");
        Path metadata = output.resolve("META-INF");
        Files.createDirectories(metadata);
        try {
            Files.createSymbolicLink(metadata.resolve("linked.kotlin_module"), target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }

        assertEquals(
                IncrementalCompileOutputDigests.fromClasses(List.of()),
                capture());
    }

    @Test
    void classOnlyCalculationPreservesTheExistingDigestFormula() {
        IncrementalCompileState.ClassRecord classRecord = new IncrementalCompileState.ClassRecord(
                "com.example.Api",
                output.resolve("com/example/Api.class"),
                "class-hash",
                "public-hash",
                "package-hash",
                0x0001,
                Optional.of("java.lang.Object"),
                List.of());

        IncrementalCompileOutputDigests digests =
                IncrementalCompileOutputDigests.fromClasses(List.of(classRecord));

        assertEquals(
                IncrementalCompileInputHasher.hashText("com.example.Api|public-hash"),
                digests.publicAbiDigest());
        assertEquals(
                IncrementalCompileInputHasher.hashText("com.example.Api|package-hash"),
                digests.packagePrivateAbiDigest());
        assertEquals(
                IncrementalCompileInputHasher.hashText("com.example.Api|class-hash"),
                digests.outputManifestDigest());
    }

    private IncrementalCompileOutputDigests capture() {
        return IncrementalCompileOutputDigests.capture(output, List.of());
    }

    private Path write(String relative, String content) throws IOException {
        Path path = output.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static void assertAllDigestsChanged(
            IncrementalCompileOutputDigests before,
            IncrementalCompileOutputDigests after) {
        assertNotEquals(before.publicAbiDigest(), after.publicAbiDigest());
        assertNotEquals(before.packagePrivateAbiDigest(), after.packagePrivateAbiDigest());
        assertNotEquals(before.outputManifestDigest(), after.outputManifestDigest());
        assertNotEquals(compileAbi(before), compileAbi(after));
    }

    private static String compileAbi(IncrementalCompileOutputDigests digests) {
        return new IncrementalCompileSummary(
                        digests.publicAbiDigest(),
                        digests.packagePrivateAbiDigest(),
                        digests.outputManifestDigest())
                .compileAbiDigest();
    }
}
