package sh.zolt.build.fingerprint;

import sh.zolt.classpath.Classpath;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BuildFingerprintClasspathContent {
    private final BuildFingerprintFileHasher fileHasher = new BuildFingerprintFileHasher();

    List<String> compileEntries(
            Classpath classpath,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState,
            boolean cacheKeyMode) {
        if (cacheKeyMode) {
            // Content-only, path-free entries keep cache keys relocatable. Entry order remains
            // significant because javac resolves duplicate classes from the first matching entry.
            return classpath.entries().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .map(path -> fileHasher.classpathKeyHash(path, cachedState, collectedState))
                    .toList();
        }
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .map(path -> path + "|" + fileHasher.classpathHash(path, cachedState, collectedState))
                .toList();
    }

    /**
     * The processor path is hashed by content, never by ABI.
     *
     * <p>A compile classpath entry that is a workspace output is summarized by its ABI because javac
     * reads only signatures. A processor is instead run, so an implementation-only change can alter
     * every generated source despite leaving its ABI unchanged.
     */
    List<String> processorEntries(
            Classpath classpath,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState,
            boolean cacheKeyMode) {
        if (cacheKeyMode) {
            return compileEntries(classpath, cachedState, collectedState, true);
        }
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .map(path -> path + "|" + fileHasher.classpathKeyHash(path, cachedState, collectedState))
                .toList();
    }
}
