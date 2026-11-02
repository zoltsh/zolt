package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspOutputTransactionTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void abandonedTransactionLeavesLiveOutputUntouched() throws IOException {
        Path live = liveBase();
        Files.createDirectories(live.resolve("kotlin"));
        Files.writeString(live.resolve("kotlin/Previous.kt"), "previous");
        Path staging;

        try (KspOutputTransaction transaction = KspOutputTransaction.begin(root(), "main", step())) {
            staging = transaction.staging().baseDirectory();
            Files.writeString(transaction.staging().kotlinOutputDirectory().resolve("Next.kt"), "next");
            assertTrue(Files.isDirectory(staging));
            assertEquals("previous", Files.readString(live.resolve("kotlin/Previous.kt")));
        }

        assertFalse(Files.exists(staging));
        assertEquals("previous", Files.readString(live.resolve("kotlin/Previous.kt")));
    }

    @Test
    void commitReplacesTheWholeOwnedTree() throws IOException {
        Path live = liveBase();
        Files.createDirectories(live.resolve("java"));
        Files.writeString(live.resolve("java/Stale.java"), "stale");

        try (KspOutputTransaction transaction = KspOutputTransaction.begin(root(), "main", step())) {
            Files.writeString(
                    transaction.staging().kotlinOutputDirectory().resolve("Generated.kt"),
                    "generated");
            Files.writeString(
                    transaction.staging().resourceOutputDirectory().resolve("service.txt"),
                    "resource");
            transaction.commit();
        }

        assertFalse(Files.exists(live.resolve("java/Stale.java")));
        assertEquals("generated", Files.readString(live.resolve("kotlin/Generated.kt")));
        assertEquals("resource", Files.readString(live.resolve("resources/service.txt")));
        assertTrue(Files.isDirectory(live.resolve("classes")));
        assertTrue(Files.isDirectory(live.resolve("cache")));
    }

    @Test
    void validationFailureHappensBeforeStagingOrMutation() throws IOException {
        Path outside = Files.createTempDirectory(projectDirectory.getParent(), "outside-ksp-live-");
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createDirectories(liveBase().getParent());
        createSymlink(liveBase(), outside);

        assertThrows(
                BuildException.class,
                () -> KspOutputTransaction.begin(root(), "main", step()));

        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
        assertEquals(List.of(), stagingSiblings());
    }

    @Test
    void failedPublishRestoresThePreviousOutput() throws IOException {
        Path live = liveBase();
        Files.createDirectories(live.resolve("kotlin"));
        Files.writeString(live.resolve("kotlin/Previous.kt"), "previous");

        try (KspOutputTransaction transaction = KspOutputTransaction.begin(root(), "main", step())) {
            deleteRecursively(transaction.staging().baseDirectory());

            assertThrows(BuildException.class, transaction::commit);
            assertEquals("previous", Files.readString(live.resolve("kotlin/Previous.kt")));
        }
    }

    @Test
    void transactionCannotBeCommittedTwice() {
        try (KspOutputTransaction transaction = KspOutputTransaction.begin(root(), "main", step())) {
            transaction.commit();
            assertThrows(IllegalStateException.class, transaction::commit);
        }
    }

    private List<Path> stagingSiblings() throws IOException {
        Path parent = liveBase().getParent();
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (var entries = Files.list(parent)) {
            return entries.filter(path -> path.getFileName().toString().contains(".zolt-ksp-staging-"))
                    .toList();
        }
    }

    private GeneratedSourceStep step() {
        return new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/main/symbols",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
    }

    private Path liveBase() {
        return root().resolve("target/generated/ksp/main/symbols");
    }

    private Path root() {
        return projectDirectory.toAbsolutePath().normalize();
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
