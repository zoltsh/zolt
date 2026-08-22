package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §19.1: journals live under {@code .zolt/manifest-edits}. Something unexpected in that area
 * must never permanently block every mutation — an irrelevant entry is ignored, and an entry Zolt
 * cannot interpret fails with one exact cleanup path.
 */
final class ManifestEditJournalRobustnessTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();

    @TempDir
    private Path tempDir;

    @Test
    void strayFilesInTheJournalAreaAreIgnored() throws IOException {
        Path project = standaloneProject();
        Path journals = project.resolve(".zolt/manifest-edits");
        Files.createDirectories(journals);
        Files.writeString(journals.resolve(".DS_Store"), "junk\n");
        Files.writeString(journals.resolve("README.txt"), "notes\n");

        edit(project);

        assertTrue(Files.readString(project.resolve("zolt.toml")).contains("added = \"1.0.0\""));
        assertTrue(Files.exists(journals.resolve(".DS_Store")), "an irrelevant file is left alone");
    }

    @Test
    void aJournalDirectoryWithNoStateIsCleanedRatherThanWedging() throws IOException {
        Path project = standaloneProject();
        Path stray = project.resolve(".zolt/manifest-edits/not-a-journal");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("leftover"), "x\n");

        edit(project);

        assertFalse(Files.exists(stray), "a journal with no state records no on-disk change");
        assertTrue(Files.readString(project.resolve("zolt.toml")).contains("added = \"1.0.0\""));
    }

    @Test
    void anUninterpretableJournalFailsWithOneExactCleanupPath() throws IOException {
        Path project = standaloneProject();
        Path stray = project.resolve(".zolt/manifest-edits/not base64");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("state"), "STAGING\n");
        Files.writeString(stray.resolve("manifest-root"), "../escape\n");

        ZoltConfigException failure = assertThrows(ZoltConfigException.class, () -> edit(project));

        assertTrue(failure.getMessage().contains(stray.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("Remove"), failure.getMessage());
    }

    @Test
    void aFileWhereTheJournalAreaBelongsFailsWithOneExactCleanupPath() throws IOException {
        Path project = standaloneProject();
        Files.createDirectories(project.resolve(".zolt"));
        Files.writeString(project.resolve(".zolt/manifest-edits"), "not a directory\n");

        ZoltConfigException failure = assertThrows(ZoltConfigException.class, () -> edit(project));

        assertTrue(
                failure.getMessage().contains(project.resolve(".zolt/manifest-edits").toString()),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("Delete"), failure.getMessage());
    }

    /** Design §19.1 names the standalone journal {@code .zolt/manifest-edits/project}. */
    @Test
    void theStandaloneJournalLivesUnderTheSpecifiedPath() throws IOException {
        Path project = standaloneProject();

        edit(project);

        assertEquals(
                project.resolve(".zolt/manifest-edits/project"),
                ManifestMutationScope.discover(project, project).transactionDirectory());
    }

    private ManifestEditResult edit(Path project) {
        return ManifestEditTransaction.execute(
                project,
                tempDir.resolve("cache"),
                true,
                MANIFESTS,
                null,
                current -> AuthoredManifestMutator.setVersionAlias(
                        current, new LocalId("added"), new VersionAliasValue("1.0.0")));
    }

    private Path standaloneProject() throws IOException {
        Path project = Files.createTempDirectory(tempDir, "project-");
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21
                """);
        return project;
    }
}
