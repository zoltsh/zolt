package sh.zolt.build.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sh.zolt.classpath.Classpath;

/** Completes the compiler-owned directory layout after restoring processor-produced classes. */
public final class ProcessorBuildCacheRestore {
    private ProcessorBuildCacheRestore() {
    }

    public static BuildCacheRestoreResult complete(
            BuildCacheRestoreResult restore,
            Classpath processorClasspath,
            Path generatedSourcesDirectory) {
        if (!restore.restored() || processorClasspath.entries().isEmpty()) {
            return restore;
        }
        try {
            Files.createDirectories(generatedSourcesDirectory);
            return restore;
        } catch (IOException exception) {
            // The caller will run a full compile, which resets any classes already extracted by the
            // incomplete restore before writing fresh compiler output.
            return BuildCacheRestoreResult.miss();
        }
    }
}
