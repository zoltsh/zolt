package sh.zolt.build.packageplan;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command-scoped content identities for compiled workspace outputs.
 */
public final class PackageOutputFingerprintIndex {
    private final Map<Path, String> fingerprints =
            new ConcurrentHashMap<>();

    public String fingerprint(Path outputDirectory) {
        Path normalized =
                outputDirectory.toAbsolutePath().normalize();
        return fingerprints.computeIfAbsent(
                normalized,
                PackageInputFingerprinting::applicationOutputFingerprint);
    }

    public int size() {
        return fingerprints.size();
    }
}
