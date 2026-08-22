package sh.zolt.build.packageevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.PackageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

final class PackageEvidenceArtifactTest {
    @Test
    void keepsDeterministicArtifactEvidenceFields() {
        PackageEvidenceArtifact artifact = new PackageEvidenceArtifact(
                "sources",
                "jar",
                "target/demo-0.1.0-sources.jar",
                12,
                "sha256:abc123");

        assertEquals("sources", artifact.classifier());
        assertEquals("jar", artifact.type());
        assertEquals("target/demo-0.1.0-sources.jar", artifact.path());
        assertEquals(12, artifact.entries());
        assertEquals("sha256:abc123", artifact.sha256());
    }

    @Test
    void rejectsMissingEvidenceFieldsWithActionableMessages() {
        assertFailure(
                () -> new PackageEvidenceArtifact("", "jar", "target/demo.jar", 1, "sha256:abc"),
                "Package evidence artifact classifier is required.");
        assertFailure(
                () -> new PackageEvidenceArtifact("main", " ", "target/demo.jar", 1, "sha256:abc"),
                "Package evidence artifact type is required.");
        assertFailure(
                () -> new PackageEvidenceArtifact("main", "jar", null, 1, "sha256:abc"),
                "Package evidence artifact path is required.");
        assertFailure(
                () -> new PackageEvidenceArtifact("main", "jar", "target/demo.jar", 1, ""),
                "Package evidence artifact checksum is required.");
    }

    private static void assertFailure(Executable executable, String message) {
        PackageException exception = assertThrows(PackageException.class, executable);

        assertEquals(message, exception.getMessage());
    }
}
