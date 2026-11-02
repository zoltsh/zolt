package sh.zolt.release.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class NativeVersionService {
    private static final String BINARY_NAME = "zolt";
    private static final String PREVIOUS_VERSION_FILE = "previous-version";

    public NativeVersionListResult list(NativeVersionListRequest request) {
        try {
            NativeInstalledLayout installed = NativeInstalledLayout.detect(request.installRoot(), request.currentExecutable());
            Path versionsRoot = NativeInstallPaths.ownedDirectory(installed.versionsDirectory(), "native versions directory");
            List<NativeInstalledVersion> versions = installedVersions(versionsRoot, installed.version());
            return new NativeVersionListResult(installed.version(), versions);
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not list installed native Zolt versions: " + exception.getMessage(), exception);
        }
    }

    public NativeVersionSwitchResult use(NativeVersionSwitchRequest request) {
        try (NativeInstallLease lease = NativeInstallLease.acquireManaged(request.installRoot(), request.currentExecutable())) {
            return useLocked(lease.root(), request.version());
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not switch native Zolt version: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeUpdateException("Could not switch native Zolt version: interrupted.", exception);
        }
    }

    private NativeVersionSwitchResult useLocked(Path root, String requestedVersion) throws IOException, InterruptedException {
        NativeInstalledLayout installed = NativeInstalledLayout.detect(root, root.resolve("bin/zolt"));
        String version = safeVersion(requestedVersion);
        if (installed.version().equals(version)) {
            return new NativeVersionSwitchResult(installed.version(), version, false, installed.binLink());
        }

        Path versionsRoot = NativeInstallPaths.ownedDirectory(installed.versionsDirectory(), "native versions directory");
        Path installDirectory = NativeInstallPaths.resolveUnderRoot(versionsRoot, version, "native install directory");
        if (!Files.isDirectory(installDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new NativeUpdateException("Native Zolt version `" + version + "` is not installed under " + versionsRoot + ".");
        }
        Path installedBinary = installDirectory.resolve("bin").resolve(BINARY_NAME).normalize();
        NativeInstallPaths.assertUnderRoot(installDirectory, installedBinary, "native installed binary");
        if (!Files.isExecutable(installedBinary)) {
            throw new NativeUpdateException("Native Zolt version `" + version + "` is not installed under " + versionsRoot + ".");
        }
        NativeUpdateVerifier.smokeCandidate(installedBinary, version);

        Path binRoot = NativeInstallPaths.ownedDirectory(installed.binLink().getParent(), "native bin directory");
        Path nextTarget = Path.of("../versions", version, "bin", BINARY_NAME);
        NativeInstallPaths.assertUnderRoot(versionsRoot, binRoot.resolve(nextTarget), "native version symlink target");
        Path canonicalBinLink = binRoot.resolve(installed.binLink().getFileName());
        writePreviousVersion(installed.installRoot(), installed.version());
        NativeInstallPaths.switchCurrent(binRoot, canonicalBinLink, nextTarget);
        return new NativeVersionSwitchResult(installed.version(), version, true, installed.binLink());
    }

    public NativeVersionSwitchResult rollback(NativeVersionListRequest request) {
        try (NativeInstallLease lease = NativeInstallLease.acquireManaged(request.installRoot(), request.currentExecutable())) {
            String previousVersion = readPreviousVersion(lease.root());
            return useLocked(lease.root(), previousVersion);
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not roll back native Zolt: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeUpdateException("Could not roll back native Zolt: interrupted.", exception);
        }
    }

    public NativeVersionPruneResult prune(NativeVersionPruneRequest request) {
        if (request.keep() < 1) {
            throw new NativeUpdateException("Native Zolt prune keep count must be at least 1.");
        }
        try (NativeInstallLease lease = NativeInstallLease.acquireManaged(request.installRoot(), request.currentExecutable())) {
            NativeInstalledLayout installed = NativeInstalledLayout.detect(lease.root(), lease.root().resolve("bin/zolt"));
            Path versionsRoot = NativeInstallPaths.ownedDirectory(installed.versionsDirectory(), "native versions directory");
            Optional<String> previousVersion = readPreviousVersionIfRecorded(installed.installRoot());
            List<NativeInstalledVersion> versions = installedVersions(versionsRoot, installed.version());

            Set<String> protectedVersions = new LinkedHashSet<>();
            protectedVersions.add(installed.version());
            previousVersion.ifPresent(protectedVersions::add);

            List<NativeInstalledVersion> newestFirst = versions.stream()
                    .sorted(Comparator.comparing(NativeInstalledVersion::version, NativeVersionOrder.INSTANCE).reversed())
                    .toList();
            Set<String> kept = new LinkedHashSet<>();
            for (NativeInstalledVersion version : newestFirst) {
                if (protectedVersions.contains(version.version())) {
                    kept.add(version.version());
                }
            }
            int effectiveKeep = Math.max(request.keep(), kept.size());
            for (NativeInstalledVersion version : newestFirst) {
                if (kept.size() >= effectiveKeep) {
                    break;
                }
                kept.add(version.version());
            }

            List<NativeInstalledVersion> keptVersions = versions.stream()
                    .filter(version -> kept.contains(version.version()))
                    .toList();
            List<NativeInstalledVersion> prunedVersions = versions.stream()
                    .filter(version -> !kept.contains(version.version()))
                    .toList();
            if (!request.dryRun()) {
                for (NativeInstalledVersion version : prunedVersions) {
                    Path installDirectory = NativeInstallPaths.resolveUnderRoot(
                            versionsRoot,
                            version.version(),
                            "native prune install directory");
                    NativeInstallPaths.deleteIfExists(versionsRoot, installDirectory, "native prune install directory");
                }
            }

            return new NativeVersionPruneResult(
                    installed.version(),
                    previousVersion,
                    request.keep(),
                    request.dryRun(),
                    keptVersions,
                    prunedVersions);
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not prune installed native Zolt versions: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeUpdateException("Could not prune installed native Zolt versions: interrupted.", exception);
        }
    }

    static void writePreviousVersion(Path installRoot, String version) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve(PREVIOUS_VERSION_FILE).normalize();
        NativeInstallPaths.assertUnderRoot(root, file, "native previous-version file");
        Path temporary = Files.createTempFile(root, ".previous-version-", ".tmp");
        try {
            Files.writeString(temporary, version + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String readPreviousVersion(Path installRoot) throws IOException {
        Optional<String> version = readPreviousVersionIfRecorded(installRoot);
        if (version.isEmpty()) {
            throw new NativeUpdateException("No previous native Zolt version is recorded.");
        }
        return version.orElseThrow();
    }

    private static Optional<String> readPreviousVersionIfRecorded(Path installRoot) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        Path file = root.resolve(PREVIOUS_VERSION_FILE).normalize();
        NativeInstallPaths.assertUnderRoot(root, file, "native previous-version file");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String version = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (version.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(version);
    }

    private static List<NativeInstalledVersion> installedVersions(Path versionsRoot, String currentVersion) throws IOException {
        try (var stream = Files.list(versionsRoot)) {
            return stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> installedVersion(path, currentVersion))
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(NativeInstalledVersion::version))
                    .toList();
        }
    }

    private static List<NativeInstalledVersion> installedVersion(Path installDirectory, String currentVersion) {
        String version = installDirectory.getFileName().toString();
        Path executable = installDirectory.resolve("bin").resolve(BINARY_NAME);
        if (!Files.isExecutable(executable)) {
            return List.of();
        }
        return List.of(new NativeInstalledVersion(version, version.equals(currentVersion), executable));
    }

    private static String safeVersion(String version) {
        String normalized = Objects.requireNonNull(version, "version").strip();
        if (normalized.isEmpty()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("..")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new NativeUpdateException("Native Zolt version must be one installed version path segment.");
        }
        return normalized;
    }
}
