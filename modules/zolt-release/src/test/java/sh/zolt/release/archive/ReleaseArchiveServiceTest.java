package sh.zolt.release.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectMetadata;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.provenance.GitProvenance;
import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ReleaseArchiveServiceTest extends ReleaseArchiveTestSupport {
    private final ReleaseArchiveService service = new ReleaseArchiveService();

    @Test
    void assemblesTarGzArchiveForUnixTargets() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.MACOS_ARM64,
                binary,
                Path.of("dist"));

        assertEquals(projectDir.resolve("dist/zolt-0.1.0-macos-arm64.tar.gz"), result.archivePath());
        assertEquals(projectDir.resolve("dist/zolt-0.1.0-macos-arm64.tar.gz.sha256"), result.checksumPath());
        assertEquals(projectDir.resolve("dist/release-manifest.json"), result.manifestPath());
        assertEquals("zolt-0.1.0-macos-arm64", result.rootDirectory());
        assertEquals(64, result.sha256().length());
        assertEquals(6, result.fileCount());
        assertEquals(
                result.sha256() + "  zolt-0.1.0-macos-arm64.tar.gz\n",
                Files.readString(result.checksumPath()));
        assertTrue(Files.readString(result.manifestPath()).contains("\"target\": \"macos-arm64\""));
        assertTrue(Files.readString(result.manifestPath()).contains("\"sha256\": \"" + result.sha256() + "\""));
        assertEquals(List.of(
                "zolt-0.1.0-macos-arm64/",
                "zolt-0.1.0-macos-arm64/bin/",
                "zolt-0.1.0-macos-arm64/bin/zolt",
                "zolt-0.1.0-macos-arm64/VERSION",
                "zolt-0.1.0-macos-arm64/README.md",
                "zolt-0.1.0-macos-arm64/LICENSE",
                "zolt-0.1.0-macos-arm64/NOTICE",
                "zolt-0.1.0-macos-arm64/THIRD_PARTY_NOTICES"), tarEntries(result.archivePath()));
    }

    @Test
    void includesBundledWorkersWhenPackagedBesideTheCliApp() throws IOException {
        Path repoRoot = projectDir.resolve("repo");
        projectDir = repoRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");
        writeWorkerJar("../zolt-junit-worker/target/zolt-junit-worker-0.1.0.jar");
        writeWorkerJar("../zolt-javac-worker/target/zolt-javac-worker-0.1.0.jar");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals(8, result.fileCount());
        assertEquals(List.of(
                "zolt-0.1.0-linux-x64/",
                "zolt-0.1.0-linux-x64/bin/",
                "zolt-0.1.0-linux-x64/bin/zolt",
                "zolt-0.1.0-linux-x64/libexec/",
                "zolt-0.1.0-linux-x64/libexec/zolt-junit-worker.jar",
                "zolt-0.1.0-linux-x64/libexec/zolt-javac-worker.jar",
                "zolt-0.1.0-linux-x64/VERSION",
                "zolt-0.1.0-linux-x64/README.md",
                "zolt-0.1.0-linux-x64/LICENSE",
                "zolt-0.1.0-linux-x64/NOTICE",
                "zolt-0.1.0-linux-x64/THIRD_PARTY_NOTICES"), tarEntries(result.archivePath()));
    }

    @Test
    void shipsLegalDocumentsKeptAtTheWorkspaceRoot() throws IOException {
        Path workspaceRoot = projectDir.resolve("repo");
        Files.createDirectories(workspaceRoot);
        Files.writeString(
                workspaceRoot.resolve("zolt.toml"),
                "[workspace]\nname = \"zolt\"\n");
        writeLegalDocuments(workspaceRoot);
        projectDir = workspaceRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals(List.of(
                "zolt-0.1.0-linux-x64/",
                "zolt-0.1.0-linux-x64/bin/",
                "zolt-0.1.0-linux-x64/bin/zolt",
                "zolt-0.1.0-linux-x64/VERSION",
                "zolt-0.1.0-linux-x64/LICENSE",
                "zolt-0.1.0-linux-x64/NOTICE",
                "zolt-0.1.0-linux-x64/THIRD_PARTY_NOTICES"), tarEntries(result.archivePath()));
        assertEquals("third party notices\n",
                tarEntryContent(result.archivePath(), "zolt-0.1.0-linux-x64/THIRD_PARTY_NOTICES"));
    }

    @Test
    void prefersLegalDocumentsBesideTheProjectOverWorkspaceRootCopies() throws IOException {
        Path workspaceRoot = projectDir.resolve("repo");
        Files.createDirectories(workspaceRoot);
        Files.writeString(
                workspaceRoot.resolve("zolt.toml"),
                "[workspace]\nname = \"zolt\"\n");
        writeLegalDocuments(workspaceRoot);
        projectDir = workspaceRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("NOTICE"), "app notice\n");
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals("app notice\n", tarEntryContent(result.archivePath(), "zolt-0.1.0-linux-x64/NOTICE"));
    }

    @Test
    void shipsLegalDocumentsFromAVirtualWorkspaceRoot() throws IOException {
        Path workspaceRoot = projectDir.resolve("repo");
        Files.createDirectories(workspaceRoot);
        Files.writeString(
                workspaceRoot.resolve("zolt.toml"),
                "[workspace]\nname = \"zolt\"\n\n[workspace.members]\ninclude = [\"apps/zolt\"]\n");
        writeLegalDocuments(workspaceRoot);
        projectDir = workspaceRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"zolt\"\n");
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertTrue(tarEntries(result.archivePath()).containsAll(List.of(
                "zolt-0.1.0-linux-x64/LICENSE",
                "zolt-0.1.0-linux-x64/NOTICE",
                "zolt-0.1.0-linux-x64/THIRD_PARTY_NOTICES")));
    }

    @Test
    void treatsWorkspaceSubtableManifestsAsWorkspaceRoots() throws IOException {
        Path workspaceRoot = projectDir.resolve("repo");
        Files.createDirectories(workspaceRoot);
        Files.writeString(
                workspaceRoot.resolve("zolt.toml"),
                "[workspace.project]\nversion = \"0.1.0\"\n");
        writeLegalDocuments(workspaceRoot);
        projectDir = workspaceRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"zolt\"\n");
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertTrue(tarEntries(result.archivePath()).contains("zolt-0.1.0-linux-x64/NOTICE"));
    }

    @Test
    void doesNotReachOutsideTheWorkspaceForLegalDocuments() throws IOException {
        writeLegalDocuments(projectDir);
        Path workspaceRoot = projectDir.resolve("repo");
        Files.createDirectories(workspaceRoot);
        Files.writeString(
                workspaceRoot.resolve("zolt.toml"),
                "[workspace]\nname = \"zolt\"\n");
        projectDir = workspaceRoot.resolve("apps/zolt");
        Files.createDirectories(projectDir);
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals(List.of(
                "zolt-0.1.0-linux-x64/",
                "zolt-0.1.0-linux-x64/bin/",
                "zolt-0.1.0-linux-x64/bin/zolt",
                "zolt-0.1.0-linux-x64/VERSION"), tarEntries(result.archivePath()));
    }

    @Test
    void includesReleaseProvenanceWhenBuilderVersionIsAvailable() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");
        ReleaseArchiveService service = new ReleaseArchiveService(
                new ReleaseArchiveManifestWriter(),
                provenanceSource(),
                Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneOffset.UTC),
                Map.of());

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals(7, result.fileCount());
        assertTrue(tarEntries(result.archivePath()).contains("zolt-0.1.0-linux-x64/BUILD.json"));
        String build = tarEntryContent(result.archivePath(), "zolt-0.1.0-linux-x64/BUILD.json");
        assertTrue(build.contains("\"schema\": \"zolt.release-provenance.v1\""), build);
        assertTrue(build.contains("\"target\": \"linux-x64\""), build);
        assertTrue(build.contains("\"version\": \"0.1.0-zap.20260707.abcdef123456\""), build);
        assertTrue(build.contains("\"commit\": \"0123456789abcdef0123456789abcdef01234567\""), build);
        assertTrue(build.contains("\"resolutionFingerprint\": \"sha256:release-inputs\""), build);
        String manifest = Files.readString(result.manifestPath());
        assertTrue(manifest.contains("\"builder\": {"), manifest);
        assertTrue(manifest.contains("\"version\": \"0.1.0-zap.20260707.abcdef123456\""), manifest);
    }

    @Test
    void stampsOverriddenPreviewVersionAcrossArchiveNameVersionFileAndManifest() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");
        String preview = "0.1.0-alpha.1";

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config().withVersion(preview),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertEquals(
                projectDir.resolve("dist/zolt-" + preview + "-linux-x64.tar.gz"),
                result.archivePath());
        assertEquals("zolt-" + preview + "-linux-x64", result.rootDirectory());
        assertTrue(tarEntries(result.archivePath()).contains("zolt-" + preview + "-linux-x64/VERSION"));
        assertEquals(preview + "\n", versionFileContent(result.archivePath(), "zolt-" + preview + "-linux-x64"));
        assertTrue(Files.readString(result.manifestPath()).contains("\"version\": \"" + preview + "\""));
    }

    @Test
    void assemblesZipArchiveForWindowsTarget() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt.exe");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.WINDOWS_X64,
                binary,
                Path.of("dist"));

        assertEquals(projectDir.resolve("dist/zolt-0.1.0-windows-x64.zip"), result.archivePath());
        assertEquals(projectDir.resolve("dist/zolt-0.1.0-windows-x64.zip.sha256"), result.checksumPath());
        assertEquals(64, result.sha256().length());
        assertTrue(Files.readString(result.manifestPath()).contains("\"format\": \"zip\""));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(result.archivePath().toFile())) {
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/bin/zolt.exe")));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/VERSION")));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/README.md")));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/LICENSE")));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/NOTICE")));
            assertTrue(zip.stream()
                    .anyMatch(entry -> entry.getName().equals("zolt-0.1.0-windows-x64/THIRD_PARTY_NOTICES")));
        }
    }

    @Test
    void skipsLegalDocumentsWhenTheyDoNotExist() throws IOException {
        Files.writeString(projectDir.resolve("README.md"), "# Demo\n");
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_X64,
                binary,
                Path.of("dist"));

        assertTrue(tarEntries(result.archivePath()).contains("zolt-0.1.0-linux-x64/README.md"));
        assertTrue(tarEntries(result.archivePath()).stream().noneMatch(entry -> entry.endsWith("/LICENSE")));
        assertTrue(tarEntries(result.archivePath()).stream().noneMatch(entry -> entry.endsWith("/NOTICE")));
        assertTrue(tarEntries(result.archivePath()).stream()
                .noneMatch(entry -> entry.endsWith("/THIRD_PARTY_NOTICES")));
    }

    @Test
    void assemblesTarGzArchiveForLinuxArm64Target() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");

        ReleaseArchiveResult result = service.assemble(
                projectDir,
                config(),
                ReleaseTarget.LINUX_ARM64,
                binary,
                Path.of("dist"));

        assertEquals(projectDir.resolve("dist/zolt-0.1.0-linux-arm64.tar.gz"), result.archivePath());
        assertEquals("zolt-0.1.0-linux-arm64", result.rootDirectory());
        assertTrue(Files.readString(result.manifestPath()).contains("\"target\": \"linux-arm64\""));
        assertEquals(List.of(
                "zolt-0.1.0-linux-arm64/",
                "zolt-0.1.0-linux-arm64/bin/",
                "zolt-0.1.0-linux-arm64/bin/zolt",
                "zolt-0.1.0-linux-arm64/VERSION",
                "zolt-0.1.0-linux-arm64/README.md",
                "zolt-0.1.0-linux-arm64/LICENSE",
                "zolt-0.1.0-linux-arm64/NOTICE",
                "zolt-0.1.0-linux-arm64/THIRD_PARTY_NOTICES"), tarEntries(result.archivePath()));
    }

    @Test
    void tarArchiveRejectsEntryNamesThatDoNotFitUstarHeader() throws IOException {
        writeProjectFiles();
        Path binary = writeBinary("target/native/zolt");
        ProjectMetadata metadata = new ProjectMetadata(
                "zolt" + "a".repeat(110),
                "0.1.0",
                "sh.zolt",
                String.valueOf(Runtime.version().feature()),
                Optional.of("sh.zolt.Main"));

        ReleaseArchiveException exception = assertThrows(
                ReleaseArchiveException.class,
                () -> service.assemble(
                        projectDir,
                        config(metadata),
                        ReleaseTarget.LINUX_X64,
                        binary,
                        Path.of("dist")));

        assertTrue(exception.getMessage().contains("Release archive entry name is too long for tar format"));
    }

    private static BuildProvenanceSource provenanceSource() {
        return (projectRoot, environment, clock) -> new BuildProvenance(
                new GitProvenance(
                        Optional.of("0123456789abcdef0123456789abcdef01234567"),
                        Optional.of("0123456789ab"),
                        Optional.of("main"),
                        false,
                        Optional.empty()),
                clock.instant(),
                "0.1.0-zap.20260707.abcdef123456",
                "21.0.2",
                "Eclipse Adoptium",
                Optional.of("sha256:release-inputs"));
    }
}
