package sh.zolt.cli.supplychain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SbomBomTest {
    @Test
    void sbomOnABomEmitsMetadataOnlyWithAStderrNoteAndNoError(@TempDir Path tempDir) throws IOException {
        // A BOM has no zolt.lock and no dependency graph; sbom must still succeed with metadata only.
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [project]
                name = "acme-bom"
                version = "1.0.0"
                group = "com.acme.platform"

                [bom.versions]
                "org.postgresql:postgresql" = "42.7.4"
                """);

        CliTestSupport.CommandResult result = CliTestSupport.execute("sbom", "--cwd", tempDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        // The metadata component names the BOM; the pinned dependency is not a resolved component.
        assertTrue(result.stdout().contains("acme-bom"), result.stdout());
        assertTrue(!result.stdout().contains("postgresql"), result.stdout());
        // A stderr note explains the metadata-only shape, never an error.
        assertTrue(result.stderr().contains("BOM"), result.stderr());
    }
}
