package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ReleaseChannelValueModelTest {
    @Test
    void artifactDefaultsNullableOptionalFieldsToEmptyOptionals() {
        ReleaseChannelArtifact artifact = new ReleaseChannelArtifact(
                ReleaseTarget.LINUX_X64,
                "zolt-0.1.0-linux-x64.tar.gz",
                "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/zolt-0.1.0-linux-x64.tar.gz",
                null,
                null,
                "tar.gz",
                "zolt",
                null);

        assertTrue(artifact.checksumUrl().isEmpty());
        assertTrue(artifact.sha256().isEmpty());
        assertTrue(artifact.signature().isEmpty());
    }

    @Test
    void manifestCopiesArtifactsAndExposesImmutableList() {
        ReleaseChannelArtifact artifact = artifact(ReleaseTarget.LINUX_X64);
        List<ReleaseChannelArtifact> artifacts = new ArrayList<>(List.of(artifact));

        ReleaseChannelManifest manifest = new ReleaseChannelManifest(
                1,
                "stable",
                "0.1.0",
                "0123456789abcdef",
                "2026-06-28T00:00:00Z",
                artifacts);
        artifacts.clear();

        assertEquals(List.of(artifact), manifest.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> manifest.artifacts().add(artifact));
    }

    @Test
    void missingArtifactDiagnosticListsExistingTargetsDeterministically() {
        ReleaseChannelManifest manifest = new ReleaseChannelManifest(
                1,
                "stable",
                "0.1.0",
                "0123456789abcdef",
                "2026-06-28T00:00:00Z",
                List.of(
                        artifact(ReleaseTarget.WINDOWS_X64),
                        artifact(ReleaseTarget.LINUX_X64)));

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> manifest.artifactFor(ReleaseTarget.MACOS_ARM64));

        assertTrue(exception.getMessage().contains("native archive target `macos-arm64`"));
        int linux = exception.getMessage().indexOf("linux-x64");
        int windows = exception.getMessage().indexOf("windows-x64");
        assertTrue(linux >= 0, exception.getMessage());
        assertTrue(windows > linux, exception.getMessage());
    }

    private static ReleaseChannelArtifact artifact(ReleaseTarget target) {
        return new ReleaseChannelArtifact(
                target,
                "zolt-0.1.0-" + target.id() + target.archiveExtension(),
                "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/zolt-0.1.0-" + target.id() + target.archiveExtension(),
                java.util.Optional.of("https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/zolt-0.1.0-" + target.id()
                        + target.archiveExtension() + ".sha256"),
                java.util.Optional.empty(),
                target.archiveExtension().substring(1),
                target.binaryName(),
                java.util.Optional.empty());
    }
}
