package sh.zolt.build.packageevidence;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Digests observed while archives were written, so evidence never re-reads a finished artifact.
 *
 * <p>One package evidence manifest names the same archive three times — as the package, as an
 * artifact and as an output. Without this, each mention re-read and re-hashed the whole file.
 */
public final class PackageArchiveDigests {
    private final Map<Path, String> digests = new ConcurrentHashMap<>();

    public void record(Path artifactPath, String sha256) {
        if (artifactPath != null && sha256 != null && !sha256.isBlank()) {
            digests.put(artifactPath.toAbsolutePath().normalize(), sha256);
        }
    }

    public String sha256(Path artifactPath) {
        Path normalized = artifactPath.toAbsolutePath().normalize();
        String recorded = digests.get(normalized);
        return recorded != null ? recorded : digests.computeIfAbsent(
                normalized,
                PackageEvidenceChecksums::fileSha256);
    }

    public int size() {
        return digests.size();
    }
}
