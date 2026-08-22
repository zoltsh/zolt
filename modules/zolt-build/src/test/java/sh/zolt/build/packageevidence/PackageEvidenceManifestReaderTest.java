package sh.zolt.build.packageevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageMergeDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageEvidenceManifestReaderTest {
    @TempDir
    private Path tempDir;

    private final PackageEvidenceManifestReader reader = new PackageEvidenceManifestReader();

    @Test
    void readsOwnedPackageEvidenceFields() throws IOException {
        Path manifest = tempDir.resolve("demo.jar.zolt-package.json");
        Files.writeString(manifest, """
                {
                  "schema": "zolt.package-evidence.v1",
                  "project": {
                    "name": "demo"
                  },
                  "package": {
                    "archive": "target/demo-0.1.0.jar",
                    "archiveSha256": "sha256:abc123"
                  },
                  "artifacts": [
                    {
                      "classifier": "main",
                      "type": "jar",
                      "path": "target/demo-0.1.0.jar",
                      "entries": 1,
                      "sha256": "sha256:abc123"
                    },
                    {
                      "classifier": "sources",
                      "type": "jar",
                      "path": "target/demo-0.1.0-sources.jar",
                      "entries": 2,
                      "sha256": "sha256:def456"
                    }
                  ]
                }
                """);

        PackageEvidenceManifest evidence = reader.read(manifest);

        assertEquals("zolt.package-evidence.v1", evidence.schema());
        assertEquals("target/demo-0.1.0.jar", evidence.archive());
        assertEquals("sha256:abc123", evidence.archiveSha256());
        assertEquals(2, evidence.artifacts().size());
        assertEquals("sources", evidence.artifacts().get(1).classifier());
        assertEquals("target/demo-0.1.0-sources.jar", evidence.artifacts().get(1).path());
        assertEquals(2, evidence.artifacts().get(1).entries());
        assertEquals("sha256:def456", evidence.artifacts().get(1).sha256());
        assertEquals(List.of(), evidence.uberMergeDecisions());
    }

    @Test
    void readsUberMergeDecisions() throws IOException {
        Path manifest = tempDir.resolve("demo.jar.zolt-package.json");
        Files.writeString(manifest, """
                {
                  "schema": "zolt.package-evidence.v1",
                  "package": {
                    "archive": "target/demo-0.1.0.jar",
                    "archiveSha256": "sha256:abc123"
                  },
                  "artifacts": [],
                  "uberMergeDecisions": [
                    {
                      "kind": "service-descriptor",
                      "path": "META-INF/services/com.example.Plugin",
                      "target": null,
                      "sources": ["com.example:first", "com.example:second"]
                    },
                    {
                      "kind": "relocated-metadata",
                      "path": "META-INF/LICENSE.txt",
                      "target": "META-INF/zolt-uber/com/example/runtime/1.0.0/LICENSE.txt",
                      "sources": ["com.example:runtime"]
                    }
                  ]
                }
                """);

        PackageEvidenceManifest evidence = reader.read(manifest);

        assertEquals(List.of(
                new PackageMergeDecision(
                        "service-descriptor",
                        "META-INF/services/com.example.Plugin",
                        Optional.empty(),
                        List.of("com.example:first", "com.example:second")),
                new PackageMergeDecision(
                        "relocated-metadata",
                        "META-INF/LICENSE.txt",
                        Optional.of("META-INF/zolt-uber/com/example/runtime/1.0.0/LICENSE.txt"),
                        List.of("com.example:runtime"))), evidence.uberMergeDecisions());
    }

    @Test
    void rejectsMalformedPackageEvidence() throws IOException {
        Path manifest = tempDir.resolve("demo.jar.zolt-package.json");
        Files.writeString(manifest, """
                {
                  "schema": "zolt.package-evidence.v1",
                  "package": {
                    "archive": "target/demo-0.1.0.jar"
                  }
                }
                """);

        PackageException exception = assertThrows(PackageException.class, () -> reader.read(manifest));

        assertTrue(exception.getMessage().contains("is missing string field `archiveSha256`"));
        assertTrue(exception.getMessage().contains("Regenerate package evidence with `zolt package`."));
    }

    @Test
    void rejectsMalformedUberMergeDecisionSources() throws IOException {
        Path manifest = tempDir.resolve("demo.jar.zolt-package.json");
        Files.writeString(manifest, """
                {
                  "schema": "zolt.package-evidence.v1",
                  "package": {
                    "archive": "target/demo-0.1.0.jar",
                    "archiveSha256": "sha256:abc123"
                  },
                  "uberMergeDecisions": [
                    {
                      "kind": "service-descriptor",
                      "path": "META-INF/services/com.example.Plugin",
                      "target": null,
                      "sources": "com.example:first"
                    }
                  ]
                }
                """);

        PackageException exception = assertThrows(PackageException.class, () -> reader.read(manifest));

        assertTrue(exception.getMessage().contains("is missing string field `sources`"));
        assertTrue(exception.getMessage().contains("Regenerate package evidence with `zolt package`."));
    }
}
