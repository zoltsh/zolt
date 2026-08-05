package sh.zolt.release.archive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ReleaseArchiveLayout {
    private static final String JUNIT_WORKER_ARTIFACT = "zolt-junit-worker";
    private static final String JUNIT_WORKER_ARCHIVE_NAME = "zolt-junit-worker.jar";
    private static final String JAVAC_WORKER_ARTIFACT = "zolt-javac-worker";
    private static final String JAVAC_WORKER_ARCHIVE_NAME = "zolt-javac-worker.jar";

    List<ReleaseArchiveEntry> entries(
            Path projectDirectory,
            Path binary,
            String rootDirectory,
            String binaryName,
            String version,
            Optional<String> releaseProvenanceJson) {
        List<ReleaseArchiveEntry> entries = new ArrayList<>();
        entries.add(ReleaseArchiveEntry.directory(rootDirectory + "/"));
        entries.add(ReleaseArchiveEntry.directory(rootDirectory + "/bin/"));
        entries.add(ReleaseArchiveEntry.file(binary, rootDirectory + "/bin/" + binaryName, 0755));
        addWorkersIfPresent(entries, projectDirectory, rootDirectory, version);
        entries.add(ReleaseArchiveEntry.content(
                (version + "\n").getBytes(StandardCharsets.UTF_8),
                rootDirectory + "/VERSION",
                0644));
        releaseProvenanceJson.ifPresent(json -> entries.add(ReleaseArchiveEntry.content(
                json.getBytes(StandardCharsets.UTF_8),
                rootDirectory + "/BUILD.json",
                0644)));
        addIfPresent(entries, projectDirectory.resolve("README.md"), rootDirectory + "/README.md", 0644);
        addLegalDocuments(entries, projectDirectory, rootDirectory);
        return entries;
    }

    private static void addLegalDocuments(
            List<ReleaseArchiveEntry> entries,
            Path projectDirectory,
            String rootDirectory) {
        for (String name : ReleaseArchiveLegalDocuments.NAMES) {
            ReleaseArchiveLegalDocuments.resolve(projectDirectory, name)
                    .ifPresent(document -> entries.add(
                            ReleaseArchiveEntry.file(document, rootDirectory + "/" + name, 0644)));
        }
    }

    private static void addWorkersIfPresent(
            List<ReleaseArchiveEntry> entries,
            Path projectDirectory,
            String rootDirectory,
            String version) {
        List<WorkerArtifact> workers = List.of(
                new WorkerArtifact(JUNIT_WORKER_ARTIFACT, JUNIT_WORKER_ARCHIVE_NAME),
                new WorkerArtifact(JAVAC_WORKER_ARTIFACT, JAVAC_WORKER_ARCHIVE_NAME));
        List<WorkerJar> presentWorkers = workers.stream()
                .map(worker -> new WorkerJar(
                        workerJar(projectDirectory, version, worker.artifact(), worker.archiveName()),
                        worker.archiveName()))
                .filter(worker -> Files.isRegularFile(worker.path()))
                .toList();
        if (presentWorkers.isEmpty()) {
            return;
        }
        entries.add(ReleaseArchiveEntry.directory(rootDirectory + "/libexec/"));
        for (WorkerJar worker : presentWorkers) {
            entries.add(ReleaseArchiveEntry.file(
                    worker.path(),
                    rootDirectory + "/libexec/" + worker.archiveName(),
                    0644));
        }
    }

    private static Path workerJar(
            Path projectDirectory,
            String version,
            String artifact,
            String archiveName) {
        String fileName = artifact + "-" + version + ".jar";
        List<Path> candidates = new ArrayList<>();
        candidates.add(projectDirectory.resolve("target/libexec").resolve(archiveName));
        candidates.add(projectDirectory.resolve("target").resolve(fileName));
        candidates.add(projectDirectory.resolve("apps/" + artifact + "/target").resolve(fileName));
        siblingWorkerJar(projectDirectory, artifact, fileName).forEach(candidates::add);
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(candidates.getFirst().toAbsolutePath().normalize());
    }

    private static List<Path> siblingWorkerJar(Path projectDirectory, String artifact, String fileName) {
        Path parent = projectDirectory.getParent();
        if (parent == null || projectDirectory.getFileName() == null || parent.getFileName() == null) {
            return List.of();
        }
        if (!"zolt".equals(projectDirectory.getFileName().toString())
                || !"apps".equals(parent.getFileName().toString())) {
            return List.of();
        }
        return List.of(parent.resolve(artifact).resolve("target").resolve(fileName));
    }

    private static void addIfPresent(List<ReleaseArchiveEntry> entries, Path source, String name, int mode) {
        if (Files.isRegularFile(source)) {
            entries.add(ReleaseArchiveEntry.file(source, name, mode));
        }
    }

    private record WorkerArtifact(String artifact, String archiveName) {
    }

    private record WorkerJar(Path path, String archiveName) {
    }
}
